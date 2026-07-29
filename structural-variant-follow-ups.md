# Variant-scoped structure — follow-ups / open items

Tracking list for work deferred on the structural-variant stack (this branch, on top of
the columnar variant storage PR). The shipped code is green on a full reactor run
(93 modules, 10 408 tests, 0 failures).

Note on vocabulary: there is **no** state-vs-structural distinction any more. There is one
kind of variant; whether an implementation stores the divergence densely or partially is
internal, exactly as in powsybl-network-store. `VariantCloneStrategy` is gone from the API.

## Functional limits (deferred by design)

- [ ] **Container and switch structural edits are guarded, not supported.** In a variant
      you cannot remove a voltage level / substation / switch / bus, nor add a switch or
      voltage level onto *shared* structure — these throw a clear exception
      (`rejectSharedStructuralEdit`, `rejectStructuralEditOnSharedContainer`) instead of
      silently corrupting other variants. Supported: add variant-scoped equipment, remove
      connectables, extend containers created in the same variant. Decide guard-vs-implement
      for full variant-scoped containers / switches / node-breaker internals.
- [ ] **Aliases are still global**, not variant-scoped. Same-*id*-across-variants works
      (multi-valued index); same-*alias*-across-variants does not.
- [ ] The non-terminal `NumericVariantStore` row leak (rows not freed on removal) rides
      underneath from the columnar PR.

## Closing the rest of powsybl-core#721

#721 asks for variants that are safe to create **on demand, concurrently, from any thread**.
What is delivered here: concurrent clone, concurrent structural divergence (removal *and*
creation), and concurrent state writes, all on a shared network — but **into pre-reserved
capacity**. `preAllocateVariants(int)` is therefore a hard contract, not a performance hint:
overflow while multi-thread access is enabled throws rather than growing the arrays on a
worker thread.

- [x] **The four eager growers are gone.** `ShuntCompensatorImpl`'s two section counts moved
      into int columns of the `NumericVariantStore` row it already owned;
      `BusTerminal.connectableBusId`, `ConfiguredBusImpl.terminals` and the node-breaker
      fictitious injection maps moved to `VariantRefArray`, which grows by appending chunks
      so an in-flight write to an existing chunk cannot be lost. Every `MultiVariantObject`
      now grows its per-variant state safely off the main thread.
- [ ] **Flip the overflow path** so `preAllocateVariants` becomes a pure hint: today
      `VariantManagerImpl` still throws rather than extending while multi-thread access is
      enabled. One hazard has to be closed first, and it is *currently unreachable only
      because* that throw is there — so it must be fixed in the same change, not after:

      An object's constructor reads `getVariantArraySize()` (under the variant lock) and
      sizes its per-variant state, then publishes itself in the index (under the index write
      lock) as a separate step. A clone that runs entirely between those two points grows
      every object in its stateful-objects snapshot — which cannot contain the new object,
      as it is not published yet — and leaves the new object one slot short. Reading the new
      variant on it then goes out of bounds.

      Two ways out, both viable; the lock order is not the obstacle, since `removeVariant`
      already establishes variant-lock-then-write-lock and either option follows it:
      - hold the variant lock across construction *and* publication, so a clone cannot
        interleave. Correct and simple to reason about, but object construction happens in
        adders all over the codebase rather than at one choke point.
      - top up short objects after the extend cascade, by re-reading the stateful list once
        the growth is published. Contained, but `MultiVariantObject` has no way to report
        how many variant slots it currently has, so it needs a small API addition.

      Worth measuring the second one's cost before choosing: it adds work to every clone,
      whereas the first only adds contention to concurrent creation.
- [ ] **Size `VariantRefArray`'s chunks deliberately.** They are 8 slots, chosen so the spine
      stays a single entry for typical variant counts. A single-variant network therefore
      allocates 8 reference slots per object where an `ArrayList` held about one — a few MB
      across the `BusTerminal`s of a large node-breaker network. The memory side of that
      trade was not measured.
- [x] **Multi-core soak.** Done, and it earned its keep. The development sandbox is genuinely
      4-core (measured: 2.04× speedup at 2 threads, 4.02× at 4, flat at 8) — an earlier note
      here claiming it was effectively single-core was simply wrong, and it had been used to
      defer this item. Soaking the variant concurrency tests surfaced a null
      stateful-objects list at a rate of about 1 run in 30, which no single run of the suite
      had ever shown (fixed: `NetworkIndex.getStatefulObjects` read its volatile cache twice).
- [ ] **Keep soaking in CI.** A rate of 1-in-30 needs hundreds of runs to confirm dead, not
      one green suite: zero failures in N runs only bounds the rate at roughly 3/N. Wire a
      periodic soak of the variant concurrency tests into CI rather than relying on the
      per-commit suite, which runs each test once and would not have caught this.

## Newer code to scrutinise / harden

- [ ] **Membership freeze edge cases:** re-attach-after-detach, node-breaker branch-attach
      with switches on variant-added voltage levels, deep or interleaved fork trees. Add
      adversarial tests.
- [ ] **The base-write guard is coarse by design.** Writing a variant that other threads
      have forked from is rejected once a second thread has bound a working variant
      (`VariantCowState.checkWritable` + `VariantContext.isSharedAcrossThreads`). It is the
      push-down of the base's value into not-yet-diverged children that races, so a
      per-slot "already materialized" bit would let base writes stay legal during the
      parallel region — at the cost of an atomic on the columnar write hot path. Parked
      unless a caller actually needs to write the base mid-region.
- [ ] **Fork chains are forbidden during the parallel region, not supported.** A clone made
      while multi-thread access is enabled must fork from the base variant; forking from
      another clone throws. Lifting this needs the same per-slot materialization above.

## Known minor gaps

- [ ] **Same-id serialization test.** An id can resolve to a different object per variant;
      add a targeted `NetworkSerDe` test for a variant whose id resolves to a shadow
      (`StructuralVariantSerializationTest` may not cover it).
- [ ] `everScoped` / `everAdded` in `VariantScopedExistence` only shrink on physical
      removal (minor retention; matches prior behaviour).

## Housekeeping

- [x] Remove the unused `Cow*` prototype classes.
- [x] Remove `VariantCloneStrategy` and the two-kind API.
- [ ] Decide whether the `structural-variant-*.md` design notes and the
      `structural-variant-olf-it` benchmark module belong in the repo long-term. The
      benchmark module is tracked but is **not** a module in the root `pom.xml`, so nothing
      builds it — either wire it in or drop it.
- [ ] Retarget to `main` once the columnar PR merges.
