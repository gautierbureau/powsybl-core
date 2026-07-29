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

- [ ] **Make `preAllocateVariants` a pure hint.** Four classes still hold their own growable
      per-variant lists, grown eagerly under `extendVariantArraySize`, and it is that growth
      that cannot happen off the main thread:
      - `BusTerminal.connectableBusId`
      - `ConfiguredBusImpl.terminals`
      - `ShuntCompensatorImpl.sectionCount` / `solvedSectionCount`
      - `NodeBreakerTopologyModel.fictitiousP0ByNodeAndVariant` / `fictitiousQ0ByNodeAndVariant`

      Every other `MultiVariantObject` is already backed by the columnar stores, whose
      per-variant bands are published copy-on-write through a volatile and so grow safely.
      Converting these four to the same volatile copy-on-write publication removes the last
      reason for the reservation, after which overflow can grow instead of throwing.
- [ ] **Multi-core soak on real hardware.** `VariantConcurrentStructuralDivergenceTest`
      (32 workers, repeated) passes here, but the sandbox is effectively single-core, so it
      exercises interleaving rather than true parallelism. Run it under real parallelism in
      CI before treating the concurrency claims as proven.

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
