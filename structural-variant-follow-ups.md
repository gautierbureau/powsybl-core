# Structural variants — follow-ups / open items

Tracking list for work deferred or worth improving on the structural-variant stack
(PR "columnar CoW engine" → PR "variant-scoped structure"). Everything below is a
follow-up; the shipped code is green (iidm-impl and iidm-serde suites, checkstyle).

## Functional limits (deferred by design)

- [ ] **Container / switch structural edits are guarded, not supported.** In a
      structural variant you cannot remove a voltage level / substation / switch /
      bus, nor add a switch or voltage level onto *shared* structure — these throw a
      clear exception (`rejectSharedStructuralEdit` / `rejectStructuralEditOnSharedContainer`)
      instead of silently corrupting other variants. Supported: add variant-scoped
      equipment, remove connectables, extend containers created in the same variant.
      Decide guard-vs-implement for full variant-scoped containers / switches /
      node-breaker internals.
- [ ] **Aliases are still global**, not variant-scoped. Same-*id*-across-variants
      works (multi-valued index); same-*alias*-across-variants does not.
- [ ] **PR #5 deferrals ride underneath:** the non-terminal `NumericVariantStore`
      row leak (rows not freed on removal) and the non-volatile store-geometry note.

## Newer code to scrutinise / harden

- [ ] **Concurrency is unproven on real hardware.** The CoW existence/membership and
      the lazy-`cowBands` fix were verified functionally on a 1-core sandbox only.
      Add a multi-core CI soak test: many workers, one structural variant each,
      shared parent unwritten.
- [ ] **Document + test the freeze-on-write concurrency caveat for existence /
      membership** (inherited from the state stores): concurrently writing a variant
      while reading one of its copy-on-write descendants needs external
      synchronisation.
- [ ] **Membership freeze edge cases:** re-attach-after-detach, node-breaker
      branch-attach with switches on variant-added VLs, deep/interleaved fork trees.
      Add adversarial tests.

## Known minor gaps

- [ ] **Same-id serialization test.** Finding-2 lets an id resolve to a shadow object
      per variant; add a targeted `NetworkSerDe` test for a variant whose id resolves
      to a shadow (existing `StructuralVariantSerializationTest` may not cover it).
- [ ] `everScoped` / `everAdded` in `VariantScopedExistence` only shrink on physical
      removal (minor retention; matches prior behaviour).

## Housekeeping

- [x] Remove the unused `Cow*` prototype classes.
- [ ] **Retarget the stack to `main`.** PR #5 (and therefore the whole stack)
      currently targets `claude/iidm-cgmes-performance-c8931j`.
- [ ] Decide whether the `structural-variant-*.md` design notes and the
      `structural-variant-olf-it` benchmark module belong in the repo long-term.
- [ ] The re-split squashed the granular development history into one commit per PR
      (fine for diff review; note if commit-level history is wanted).

## Scoping: the *full* powsybl-core#721 on top of this stack (concurrent on-demand structural clone)

powsybl-core#721 ("thread-safe variants through array-based representation") has two
halves: (1) the array/SoA storage — delivered by PR #5; and (2) the *headline* ask —
a variant clone that is safe to call **on demand, concurrently, from any thread**, with
no need to pre-create every variant up front. This stack's `STRUCTURAL` clone is O(1)
(copies no rows), which is the "cheap append" #721 envisioned, but structural clone is
still **main-thread-only** today. This section scopes closing half (2) on top of the CoW
engine, and how it relates to the pre-allocation PR
(`claude/iidm-variant-preallocation`, "Thread-safe on-demand variant creation via variant
pre-allocation", opened against PR #5 — referred to below as **the pre-alloc PR**).

### What a `STRUCTURAL` clone mutates, and whether it is concurrent-safe today

| # | Mutated on a structural clone | Published how | Read on the hot path? | Concurrent-clone-safe now? |
|---|---|---|---|---|
| 1 | `id2index` / `variantArraySize` / `unusedIndexes` (`VariantManagerImpl`) | plain fields | on `setWorkingVariant`, create/remove | **No** — this is exactly what the pre-alloc PR fixes (a `variantLock` serialising the id↔index bookkeeping + reserved-capacity handout) |
| 2 | per-store `cowBands[]` (`extendStructural`) | `volatile CowBand[]`, `copyOf` preserves entries | yes (`resolve`/`bandOf`) | **Nearly** — volatile CoW-list-style growth is reader-safe; only gated main-thread-only by convention |
| 3 | `VariantCowState.parent[]/cow[]/cowChildren[][]` (`recordClone` → `rebuildDerivedState`) | **non-volatile**, whole arrays rebuilt every clone | **yes** — every variant-dependent read resolves up `getParent`/`getCowChildren` when the CoW gate is active | **No** — the real blocker |
| 4 | freeze-on-write (`NetworkImpl.materializeCowInheritorsOf`) | writes into children's bands | on every write to a CoW *parent* | **No** — the documented freeze race |

Row 1 is the pre-alloc PR's job; rows 2–4 are new work the CoW engine introduces and are
**not** covered by it.

### Two scopes

- [~] **B1 — bounded concurrent structural clone (recommended; the practical full-#721).**
      `CoW engine` + **the pre-alloc mechanism ported onto this stack** + a **`VariantCowState`
      concurrency pass**. Workers create structural variants **on demand, concurrently,
      O(1)**, each cloning from the shared base, up to a reserved capacity. This is what
      OLF / OpenRao actually need. **Core implemented on this branch** (see
      `VariantPreAllocationStructuralCloneTest`); the two open items below remain.
  - Row 4 (freeze race) **disappears by construction** in the disciplined pattern *clone
    from the unwritten base, each worker writes only its own leaf variant* (a leaf has no
    CoW children, so `freezeInheritors` is a no-op) — the very pattern already documented
    as safe. The concurrent test uses it; nothing yet *enforces* it (see below).
  - Work items:
    - [x] Port the pre-alloc mechanism (`preAllocateVariants`, `variantLock`, `preAllocated`
          guards, overflow-throws / no-shrink) onto this branch, threaded through the
          `STRUCTURAL` clone path. Done: `VariantManagerImpl` runs every clone/remove under
          `variantLock`; reservation drives the same `structural=true` extend cascade a
          structural clone uses, so it needs **no new store or `MultiVariantObject` API**.
    - [x] Pre-size the CoW metadata via the reservation: `preAllocateVariants` grows each
          store's `cowBands` table (through `extendStructural`) so a worker's structural
          claim (`allocateStructural`) and any divergent write (`ensureBand`) never grow
          `cowBands` off the main thread. Dense arrays / eager trove state grow too.
    - [x] **`VariantCowState` concurrency pass** (the one genuinely new item): `parent` /
          `cow` / `cowChildren` are now bundled into an immutable `State` published through
          a single `volatile` reference. Readers (`getParent` / `getCowChildren` / `isCow` /
          `isActive`) take one volatile read and see a consistent tuple with no lock;
          `recordClone` / `forgetVariant` build a fresh `State` and swap it in (serialized by
          `variantLock`). Replaces the previous non-volatile in-place rebuild.
    - [ ] **Enforce the clone-from-unwritten-base guard.** Today the safe pattern (fork from
          a variant no other thread is writing) is a convention the test follows but nothing
          rejects a concurrent clone-from-a-written-parent, which would hit the freeze race.
          Add a fail-fast guard (or a documented precondition) before relying on it in OLF.
    - [ ] **Multi-core soak test on real hardware.** The concurrent test passes here but the
          sandbox is effectively single-core; run it under real parallelism (supersedes the
          "concurrency unproven" item above for the structural path).
- [ ] **B2 — unbounded, arbitrary concurrent clone (sylvlecl's literal
      `CopyOnWriteArrayList`, no pre-alloc).** Everything in B1 minus pre-allocation, plus
      fully CoW-published growable tables everywhere **and** a general solution to the
      freeze race (clone from any variant while another thread writes it). Research-grade,
      high risk, beyond any stated need. Reuses the pre-alloc PR's lock/guards but drops
      its pre-allocation. **Parked** unless a concrete need appears.

### Recommendation

Pursue **B1**. Beyond porting the pre-alloc PR, the single genuinely new engineering item
is the `VariantCowState` publication pass; the freeze race is *avoided* by the
clone-from-base discipline rather than solved in general. B2 stays parked.
