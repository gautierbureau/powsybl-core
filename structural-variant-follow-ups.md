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
