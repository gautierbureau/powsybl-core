# Structural variants — public API proposal (network-store-inspired)

A proposal for exposing the structural-variant capability (prototyped on this branch as
`VariantScopedExistence` / `VariantScopedMembership` / `CowVariantColumn`) as a **public IIDM API**. The
guiding principle is taken directly from `powsybl-network-store`, which already ships this in production:

> **A structural variant is just a variant.** There is no new "branch" object and almost no new API.
> The user clones a variant with the ordinary `VariantManager`, then mutates the network with the
> ordinary API (`newGenerator().add()`, `identifiable.remove()`, the split-line modifications). Those
> mutations are automatically scoped to the working variant. Reads (`network.getGenerator(id)`,
> `voltageLevel.getConnectables()`, the bus view) resolve against the active variant.

Everything the spike does with explicit opt-in calls (`enableVariantScopedExistence()`,
`existOnlyInCurrentVariant(...)`, `beginAttach(...)`) becomes an **internal implementation detail**. The
only genuinely new public surface is a single opt-in flag at clone time.

## 1. The one new public concept: a clone strategy

Today `VariantManager.cloneVariant` copies every per-variant **state** value (setpoints, tap positions,
switch open, terminal p/q) into the new variant while all variants **share one structure**. Structural
change (adding/removing an object, re-connecting a terminal) is network-wide and escapes the variant.

The proposal adds a second **capability** at clone time — the new variant may also diverge
**structurally** — chosen with a strategy enum, exactly mirroring network-store's *full → partial* clone
(`cloneNetworkVariant` overriding `fullVariantNum = sourceVariantNum`).

```java
public interface VariantManager {

    enum VariantCloneStrategy {
        /**
         * Current behaviour (default, fully backward-compatible): the target variant gets an independent
         * copy of every per-variant STATE value and shares the network STRUCTURE with all other variants.
         * Structural changes made while it is active are network-wide.
         */
        STATE_ONLY,

        /**
         * The target variant may diverge STRUCTURALLY: objects can be added, removed, or re-connected in
         * it without affecting any other variant. For a network that has at least one structural variant,
         * object existence and topology are resolved against the active variant.
         */
        STRUCTURAL
    }

    /** Clone with an explicit strategy. The existing overloads delegate with {@code STATE_ONLY}. */
    void cloneVariant(String sourceVariantId, String targetVariantId, VariantCloneStrategy strategy);
    void cloneVariant(String sourceVariantId, List<String> targetVariantIds, VariantCloneStrategy strategy, boolean mayOverwrite);

    // ... existing methods unchanged; existing overloads keep STATE_ONLY semantics ...
}
```

That is the **entire** public addition. No `StructuralBranch`, no `Network.branch()`, no per-type API.

## 2. Everything else is the API you already have

Once a `STRUCTURAL` variant is the working variant, the ordinary API is variant-scoped:

```java
VariantManager vm = network.getVariantManager();
vm.cloneVariant(INITIAL_VARIANT_ID, "expansion", STRUCTURAL);   // O(1) target (copy-on-write)
vm.setWorkingVariant("expansion");

// a "what-if we add a unit" scenario — ordinary adder, scoped to "expansion"
vl.newGenerator().setId("NEWGEN") /* ... */ .add();

// a "unit decommissioned in this scenario" — ordinary remove, scoped to "expansion"
network.getGenerator("OLDGEN").remove();

// a fault-on-line split — the existing iidm-modification, scoped to "expansion"
new CreateVoltageLevelSections(...)/* or the split-line modification */.apply(network);

vm.setWorkingVariant(INITIAL_VARIANT_ID);   // the base network is untouched
assertNull(network.getGenerator("NEWGEN"));
assertNotNull(network.getGenerator("OLDGEN"));
```

- `network.getGenerator(id)` / `getIdentifiable(id)` / `getGeneratorCount()` — variant-dependent.
- `voltageLevel.getConnectables()`, `getConnectableStream(...)`, the bus/breaker and node/breaker **bus
  views** — variant-dependent (the spike already routes all of these through the active variant).
- `NetworkSerDe.write` / `NetworkSerDe.copy` — serialize the **active variant's resolved view**; a
  structural variant flattens transparently (see §5). No new serialization API.
- The `iidm-modification` split-line family (`ConnectVoltageLevelOnLine`, `RevertConnectVoltageLevelOnLine`,
  `CreateLineOnLine`, …) needs **no change** — its `newLine()/remove()` calls scope to the active variant
  automatically. This is where the spike's `VariantScopedLineSplit` is retired: it becomes "run the
  ordinary modification inside a `STRUCTURAL` variant".

## 3. Semantics & contract

- **Snapshot.** `cloneVariant` remains a point-in-time copy: a later change to a parent variant never
  leaks into a variant cloned earlier. The copy-on-write storage (`CowVariantColumn`) preserves this by
  freezing on the write side (proven in `CowVariantColumnTest`).
- **Self-consistent variants.** Each variant is internally consistent on its own; there is no ambient
  context to enter (unlike the intermediate v2 spike). The working variant *is* the context.
- **Existence.** In a `STRUCTURAL`-capable network, "does object X exist" is answered per variant — the
  one real behavioural change, and only for networks that opt in.
- **Concurrency.** Unchanged: `allowVariantMultiThreadAccess(true)` lets different threads hold different
  working variants; structural resolution reads the per-thread active variant.
- **Removal.** Removing an object in a structural variant tombstones it there; it remains in the others.
  Removing it in a `STATE_ONLY` context (or the base) removes it network-wide, as today.

## 4. What stays internal (mapping from the spike)

| spike (this branch) | becomes |
|---|---|
| `NetworkImpl.enableVariantScopedExistence()` / `...Membership()` | internal; auto-enabled the first time a `STRUCTURAL` variant is cloned |
| `VariantScopedExistence` | internal per-variant existence in the index |
| `VariantScopedMembership` | internal per-variant terminal membership |
| `VariantScopedExistence.existOnlyInCurrentVariant` / `hideInCurrentVariant` | internal, driven by ordinary `add()` / `remove()` on a structural variant |
| `VariantScopedMembership.beginAttach` + topology-model `branchAttachIntercept` | internal, driven by ordinary adders |
| `VariantScopedLineSplit`, `VariantScopedConnectableAdd` | **deleted** — replaced by ordinary modifications / adders run in a structural variant |
| `CowVariantColumn` / `CowVariantParentage` | internal storage for per-variant state + existence (the O(1)-fork engine) |

The only spike concept that surfaces publicly is the **clone strategy**.

## 5. Serialization (flatten) — transparent

A structural variant shares base objects, so writing it naively would drop them (an object's parent is
the base network). Under this API the fix is invisible to the user: `NetworkSerDe`, when the working
variant is structural, writes the **resolved** view (base − tombstoned + added). The spike validated the
equivalent flatten (`copy(base)` + replay the delta) as byte-identical to `copy + modify`; in the
integrated form the serializer simply iterates the active variant's resolved objects. No public flatten
method is exposed.

## 6. Exact correspondence with network-store

| powsybl-core public API (this proposal) | network-store `PartialVariantUtils` / repository |
|---|---|
| `cloneVariant(src, tgt, STRUCTURAL)` | `cloneNetworkVariant` overriding `fullVariantNum = sourceVariantNum` (full → partial), copying no resources |
| `network.getGenerator(id)` on a structural variant | `getOptionalIdentifiable` — partial → (tombstoned? empty) → full |
| `getGeneratorStream()` / `getGeneratorCount()` | `getIdentifiables` — `full − (partialIds ∪ tombstonedIds) + partial` |
| `remove()` in a structural variant | write a tombstone for that variant |
| `newGenerator().add()` in a structural variant | a `partial` resource carrying the child `variantNum` |
| bus-view / connectable membership per variant | terminal membership carried at the resource's `variantNum` |
| `NetworkSerDe` of a structural variant | the read-time merge a full-variant clone would materialise |
| `STATE_ONLY` (default) | a `full` variant (base), `fullVariantNum = -1` |

The proposal is deliberately the *same model*: existence and structure resolved per variant by fall-through
to a parent, tombstones for removals, local additions surfaced at read. powsybl-core reaches it in-place
(over its existing `MultiVariantObject` machinery) instead of over opaque `Resource<Attributes>` records.

## 7. Phasing

1. **Phase 0 — mechanism (done, this branch).** Explicit opt-in (`enable...`) + `VariantScoped*`
   utilities; existence, membership, every read path, and a turnkey split proven on a single network;
   type-agnostic across generators/loads; the CoW O(1)-fork storage prototyped; measured ~15–20× faster
   and ~12–15× less memory than `NetworkSerDe.copy`.
2. **Phase 1 — public API, eager storage.** Add `VariantCloneStrategy`; auto-enable the variant-scoped
   layers on the first `STRUCTURAL` clone; route ordinary `add()`/`remove()`/modifications through them
   when the active variant is structural; teach `NetworkSerDe` to write the resolved view. Storage stays
   the current dense per-variant arrays (structural clone is O(N) like today's clone). Fully usable.

   **Phase 1 status (partially built on this branch):**
   - ✅ `VariantManager.VariantCloneStrategy` + `cloneVariant(..., strategy)` — landed (default methods;
     `VariantManagerImpl` implements `STRUCTURAL`: marks the variant structural and auto-enables the
     variant-scoped layers).
   - ✅ **`remove()` auto-scopes** — `AbstractConnectable.remove()` tombstones (hide + detach terminals)
     when the working variant is structural; network-wide otherwise (backward-compatible).
   - ✅ **`add()` auto-scopes, connectables *and* containers** — a connectable's terminals are recorded in
     the active variant's membership by the topology-model branch-attach intercept, and existence-scoping
     of *every* added object (a connectable, or a container VL/bus/substation) is handled centrally in
     `NetworkIndex.checkAndAdd`. So ordinary `newGenerator().add()` / `newLine().add()` *and*
     `newSubstation()/newVoltageLevel()/newBus()` "just work".
   - ✅ **Container-creating operations** — a fault-on-line split that adds a new fictitious voltage level
     runs entirely through the public API (`remove()` + `newSubstation()/newVoltageLevel()/newBus()` +
     `newLine()` under a `STRUCTURAL` variant). Proven by `StructuralVariantSplitViaApiTest` (bus/breaker
     and node/breaker). With this, the internal operation helpers (`VariantScopedLineSplit`,
     `VariantScopedConnectableAdd`) are **deleted** — the public API is the only path. Coverage now via
     `StructuralVariantRemoveTest`, `StructuralVariantSplitViaApiTest`, `StructuralVariantTypeAgnosticTest`
     (generator + load), and the reworked `StructuralVariantApiExampleTest` (all three examples call the
     real API). The `VariantScopedSplitBenchmarkTest` also splits via the public API now.
   - ✅ **`NetworkSerDe` resolved-view write** — writing while a structural variant is the working variant
     serializes its resolved view (base − tombstoned + added), with **no special flatten step**. In the
     single-network variant model every object's parent is the one network (unlike the earlier two-network
     overlay, where shared objects' parent was the base and were dropped), and every collection accessor
     resolves against the active variant — so the serializer needs no change. Proven by
     `StructuralVariantSerializationTest`: `NetworkSerDe.copy` of the `fault` variant round-trips to the
     split network, of `INITIAL` to the intact base, both plain networks.

   **Phase 1 is functionally complete**: a `STRUCTURAL` clone plus the ordinary IIDM API (`add`, `remove`,
   the split sequence) plus serialization all work end-to-end. Storage is still the eager per-variant
   arrays (structural clone is O(N)); Phase 2 (`CowVariantColumn`) makes it O(1).

3. **Phase 2 — copy-on-write / O(1). Started on this branch:**
   - ✅ **Variant parentage foundation** — `VariantManagerImpl` records a clone parent pointer for every
     variant (`parentVariant(index)`), maintained across recycling and re-parented on `removeVariant`.
     This is the structure every copy-on-write step reads (the `CowVariantParentage` prototype validated
     the algorithm; this is it wired into the live variant manager).
   - ✅ **Object *addition* is now O(1)** — `existOnlyInCurrentVariant` was O(variants) (it hid the new
     object in every other variant eagerly; a split adds ~5 objects, each O(variants)). It is now a single
     global `id → variant-it-was-added-in` entry, resolved at read time through the parentage: an added
     object is visible in that variant and its **descendants** only. Snapshot-correct by construction
     (the parentage encodes fork order), and `removeVariant` cleans it up (the object disappears
     everywhere; the index can be recycled). Proven by `StructuralVariantParentageTest` (descendant
     inheritance, sibling/ancestor invisibility, remove-variant cleanup). All 1025 iidm-impl + 305 serde
     tests pass.
   - ✅ **Measured at the real use case (N-1 contingency analysis).** `VariantScopedSplitBenchmarkTest`
     (`compareManyContingenciesToManyCopies`): **40 contingencies** (each removes a different line) as
     structural variants on **one** network vs 40 full `NetworkSerDe.copy` networks, on a 4 000-line grid:

     | approach | time | retained |
     |---|---|---|
     | 40 structural variants (one network) | ~170–210 ms | ~41 MB |
     | 40 full copies | ~4 200–4 300 ms | ~837 MB |
     | **payoff** | **~20–25× faster** | **~20× less memory** |

     The structural network shares the base object graph once and pays only per-variant *state* + a tiny
     structural delta per contingency; copies duplicate the whole network 40 times. This is where the
     feature earns its keep.

   - ⏳ **Bulk state O(1)** — the remaining, larger part, and a deliberate core change (not a spike
     increment). Even in the result above, each structural variant still costs ~1 MB of eager per-variant
     **state**: the columnar stores (`NumericVariantStore`/`TerminalVariantStore`/`SwitchVariantStore`)
     copy the whole source band on `VariantColumnStore.extend` (O(rows) per clone), and the per-object
     variant arrays likewise. Copy-on-write over the parentage removes that:
     - **Read** `getDouble(variant, col, row)` resolves the variant to its nearest *materialised* ancestor
       through `parentVariant`, for structural variants only; normal variants stay dense (a single gate
       check on the hot path, so no-cost when no structural variant exists).
     - **Clone** aliases the child band to the parent (O(1), copies nothing).
     - **Write** materialises copy-on-write: writing a structural variant's row copies that row from the
       parent first (per-row, so only touched rows cost anything); and — for snapshot correctness — writing
       a *parent* that still has aliased children first freezes the affected row into them. This last point
       is the crux: it is a check on the write hot path, and it is the same price network-store pays by
       resolving every read against the variant. Because it is a correctness-critical change to the
       performance-critical columnar engine (the `VariantColumnStore` implementations + the per-object
       `MultiVariantObject` arrays), it warrants its own design review rather than being rushed — this is
       the tier scored as the most invasive in `structural-variant-generic-design.md`.

     **Mechanism de-risked in isolation (prototype built).** `CowColumnarStore` models the columnar-store
     shape (a `rows × columns` grid per variant) made copy-on-write over the parentage, and
     `CowColumnarStoreTest` proves both hard properties: **fork is O(1)** (100 forks add zero stored rows;
     storage is O(diverged rows), not O(variants × rows)), and **snapshot is preserved** by freezing a row
     into inheriting children on the write side (a change to a parent never leaks into a variant forked
     earlier), **per row** so only touched rows cost anything. This is the same de-risking pattern used for
     `CowVariantColumn` (state) and `OverlayNetworkIndex` (structure) before their integration: it retires
     the design risk of the live conversion — porting `NumericVariantStore`/`TerminalVariantStore`/
     `SwitchVariantStore` (and the per-object arrays) onto this model, gated so a network with no
     structural variant stays byte-for-byte dense — without performing that hot-path change here.

     **Gating de-risked too (prototype built).** The remaining risk was not the algorithm but *coexistence*:
     the conversion must cost **nothing** for the networks (all of them today) that never use a structural
     variant, and stay correct where a copy-on-write variant is forked from a **dense** one.
     `CowGatedColumnarStore` models both storage modes in one store — dense variants (initial + `STATE_ONLY`)
     own every row (today's eager behaviour, unchanged); copy-on-write variants (`STRUCTURAL`) own only
     diverged rows and fall through the parentage — behind a single `cowActive` gate flipped on the first
     structural clone. `CowGatedColumnarStoreTest` proves: with no structural variant the store stays on the
     plain dense **fast path** (`isFastPath()`), a `STRUCTURAL` clone from a dense parent is **O(1)** (stores
     zero rows) yet **reads through** to the dense band, and a write to a **dense parent after a structural
     fork** still **freezes** the row into the copy-on-write child (snapshot across the dense→sparse
     boundary). With this, the live columnar port is a mechanical application of a fully de-risked design —
     algorithm, gating, and boundary correctness all proven in isolation.

     **Live columnar port — plan, and why it is one indivisible core change.** Tracing the live lifecycle
     establishes that the port is **all-or-nothing** and must be its own reviewed change, not a spike step:
     - Per-variant state is spread across **~52 owners** — the 3 flat-array stores *plus ~49
       `MultiVariantObject` classes* that still keep their own per-variant arrays. A `cloneVariant` copies
       all of them.
     - **No measurable win until nearly complete**: the clone cost is the *sum* of every owner's eager copy,
       so converting one store leaves the clone O(N). The O(1) win appears only once essentially all owners
       are converted.
     - **The lifecycle carries no structural signal**: `VariantManagerImpl.cloneVariant` →
       `MultiVariantObject.extendVariantArraySize` / `VariantColumnStore.extend` is generic (same path for
       `STATE_ONLY` and `STRUCTURAL`), and the flat arrays are dense, indexed by variant — a structural
       variant with no dense band can't be expressed without changing these signatures and the index→slot
       mapping.

     Sequence, when done as its own change: (1) add a structural-clone signal — a `STRUCTURAL`-aware clone
     path that marks the child + parentage before driving owners, plus lifecycle hooks on
     `MultiVariantObject`/`VariantColumnStore` whose **defaults delegate to the existing eager copy** so every
     unconverted owner stays correct; (2) convert each owner to the gated copy-on-write model proven by
     `CowGatedColumnarStore` (stores first, then the ~49 arrays), keeping the `cowActive` gate so
     non-structural networks stay byte-for-byte dense; (3) flip reads/writes to the gated resolve with
     freeze-on-parent-write. The three prototypes make each step mechanical; the spike's job — prove the
     feature works and pays off (~20× on the eager storage it has today) and de-risk the O(1) path — is done.
3. **Phase 2 — O(1) fork / full parity.** Swap the dense per-variant arrays of the `MultiVariantObject`
   classes for `CowVariantColumn` over a shared `CowVariantParentage`, and make `cloneVariant(...STRUCTURAL)`
   fork the parentage instead of allocating slots. This is the large, invasive change (the 57-class
   lifecycle rewrite scored in `structural-variant-generic-design.md`); it is transparent to the public
   API above — only performance changes.

## 8. Compatibility

- The default (`STATE_ONLY`) and every existing `cloneVariant` overload keep today's exact semantics, so
  **existing code is unaffected**.
- `getX(id)` becomes variant-dependent **only** for a network that has opted into a `STRUCTURAL` variant.
  A network that never uses one behaves and performs exactly as today (the spike's read paths are
  null-gated; all 1014 iidm-impl tests pass unchanged).
- Downstream consumers that assume object identity is variant-independent keep that guarantee unless they
  themselves request a `STRUCTURAL` variant.

## 9. Open questions for maintainer review

- **Naming.** `VariantCloneStrategy.{STATE_ONLY, STRUCTURAL}` vs network-store's `{FULL, PARTIAL}` — align
  with network-store, or prefer the capability-oriented names above?
- **Opt-in granularity.** Per-clone strategy (proposed) vs a per-network capability set at creation. A
  per-clone flag is the most flexible and matches network-store; a per-network flag makes the "reads are
  variant-dependent" cost explicit up front.
- **`removeVariant` of a structural variant** whose children inherit from it — forbid, or re-parent
  children to its parent (the CoW parentage supports re-parenting; needs a policy).
- **Merge/subnetwork interaction** with structural variants — out of scope for Phase 1; needs a rule.
