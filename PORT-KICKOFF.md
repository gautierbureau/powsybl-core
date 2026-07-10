# Kickoff — live columnar copy-on-write clone (O(1) `STRUCTURAL` clone)

This branch (`claude/iidm-columnar-cow-clone`) is the launch pad for the **last phase** of the
structural-variant work: making a `STRUCTURAL` `cloneVariant` genuinely **O(1)** by converting the
per-variant *state* storage to columnar copy-on-write.

Everything you need is already in this working tree (this branch merges the shipped feature, the
de-risked prototypes, and the written plan).

## Read first

1. **`structural-variant-public-api.md`** — the plan of record. The **"O(1) clone / live columnar port"**
   section is the port sequence: ~52 owners = the 3 flat-array stores + ~49 `MultiVariantObject` classes +
   the clone lifecycle. Note the key risk it calls out: **no aggregate win shows up until the port is
   nearly complete**, and the clone lifecycle currently has no "is this a structural clone" signal.
2. **`structural-variant-vs-network-store.md`** — the correspondence with `powsybl-network-store`
   (full vs partial variants, `fullVariantNum`, tombstones) the design mirrors.

## Port from these proven prototypes (already in `iidm-impl`)

- `CowVariantParentage` — the variant forest (each variant → its parent); O(1) fork.
- `CowVariantColumn` — one variant field, copy-on-write, snapshot-correct.
- `CowColumnarStore` — a full `rows × columns` store made copy-on-write, per-row divergence,
  freeze-on-parent-write.
- `CowGatedColumnarStore` — the **gated** variant: zero cost on the dense fast path, correct across the
  dense→sparse boundary.

Their tests (`Cow*Test`) pin the exact semantics to preserve.

## The real targets to convert

- The 3 bespoke stores: `NumericVariantStore`, `TerminalVariantStore`, `SwitchVariantStore`.
- The ~49 `MultiVariantObject` classes and the clone lifecycle
  (`VariantManagerImpl.cloneVariant` → `extendVariantArraySize` / `allocateVariantArrayElement`;
  and `VariantManagerImpl.variantParent` / `parentVariant`, already shipped, is the parentage the
  columns resolve through).

## Hard constraints (do not regress)

1. **Backward-compatible** — `STATE_ONLY` and every existing `cloneVariant` overload keep today's exact
   semantics. A network that never creates a `STRUCTURAL` variant stays on the **dense fast path**
   (the `cowActive` gate); pay nothing.
2. **Thread-safe** per the `VariantManager` contract — structural changes on the main thread only;
   pre-allocated variants read/written concurrently, each on its own band; publish via `volatile`.
3. **Byte-identical tests** — `iidm-impl`, `iidm-serde` golden files, and `cgmes-conversion` stay green.
4. **IIDM snapshot semantics** — copy-on-write happens on the *write* (freeze-on-parent-write), so a
   later change to a parent variant never leaks into a variant forked earlier.

## Suggested approach

Land it **incrementally behind the gate** — convert one store, keep every suite green, repeat — rather
than one giant commit. Because there's no aggregate win until near the end, use per-field micro-benchmarks
(see the benchmark harness `VariantScopedSplitBenchmarkTest`, `-Dbenchmark=true`) to prove each converted
store is neutral-or-better as you go.

## Housekeeping

- Base your PR on `claude/iidm-structural-variant` (PR #22).
- Delete this `PORT-KICKOFF.md` (and, if you don't want the plan docs in the shipped diff, the
  `structural-variant-*.md` files — they're preserved in PR #25) before opening the PR.
