# Structural-variant branching — killing the materialisation cascade (design)

Design note for the one remaining blocker in the structural-variant spike (phases 1–3 in
`structural-variant-spike.md`). Everything up to here works and is tested; this note is about the
piece the current code rejects with a loud error rather than getting wrong.

## 1. The problem

`BranchLineSplit` materialises the endpoint voltage levels of the split line into the branch as
copy-on-write copies (buses, switches, injections re-homed). It **rejects** an endpoint that also
hosts another *through-connectable* — a second line/transformer, e.g. `VLA --L-- VLB --M-- VLC`,
splitting `L`. Materialising `VLB` (because it hosts `L`) drags in `M`, and handling `M` naively
drags in `VLC`, then `VLC`'s neighbours… the whole connected component. That defeats the point (the
saving was "copy ~2 VLs, not the network").

## 2. Root cause (grounded in the impl)

A branch overrides the base in two independent places, and only one is currently overlay-aware:

- **Forward, terminal → voltage level.** `AbstractTerminal` holds a *direct* `VoltageLevelExt`
  pointer (`AbstractTerminal.voltageLevel`), and derives its network from it
  (`network = voltageLevel.getNetworkRef()`).
- **Reverse, voltage level → its connectables.** A VL enumerates connectables from **its own
  topology graph**: `BusBreakerTopologyModel.getTerminals()` walks the graph vertices
  (`ConfiguredBus`) and returns `ConfiguredBus::getTerminals`; node-breaker stores `NodeTerminal`s as
  graph vertices. **This read never consults the `OverlayNetworkIndex`.**

So `overlay.tombstone(M)` hides `M` from `branch.getLine("M")` (index path) but **not** from
`VLC.getConnectables()` (graph path). In the branch, `VLC` still enumerates the shared `M`, whose
other terminal is the base `VLB` — which the branch has shadowed with `VLB'`. To make `VLC`
consistent you must rebuild `VLC`'s graph (materialise it), and so on. The cascade is the index
overlay and the topology graph disagreeing about which lines are where.

## 3. What "correct" requires

A branch view in which a shared through-line `M`'s **near** attachment differs (`VLB → VLB'`) while
its **far** attachment (`VLC`) is byte-for-byte the base — with no whole-component copy. Equivalently:
make the *attachment* of a bounded set of terminals (those at the split VL) branch-overridable, in
**both** the forward and the reverse direction.

## 4. Options

**A. Per-VL topology-graph overlay.** Mirror `OverlayNetworkIndex` one level down: every VL's
terminal membership becomes a shared-base-graph + branch-delta. Correct and general, but it makes
*every* voltage level and the hot traversal path overlay-aware — broad blast radius, real per-read
cost network-wide.

**B. Branch-scoped terminal attachment (recommended).** Don't recreate the through-line; **rebind its
near terminal** to `VLB'` in the branch only. The far terminal (`VLC` side) is never touched, so
**nothing cascades** — the change is bounded to O(through-lines incident to the split VL), a handful.
Leverages machinery that already half-exists (below).

**C. Bounded / fixed-depth materialisation.** Materialise the sub-region out to some cut-set.
Rejected: for a meshed grid the cut-set is the whole component, and any truncation yields a branch
that is *wrong* for computation — strictly worse than today's honest rejection.

Recommend **B**.

## 5. Recommended design — branch-scoped terminal attachment

### Leverage points that already exist
- `BusTerminal.connectableBusId` is a **per-variant** `ArrayList<String>` (`set(variantIndex, busId)`)
  — a terminal's *bus* is already attachment-that-varies-by-index.
- `Connectable.move(terminal, node, voltageLevelId)` / `BusTerminal…moveConnectable(node, vlId)`
  already relocate a terminal to another VL/bus. Today `move` mutates in place; we need a
  **branch-scoped move** that records the new attachment in the branch context instead of mutating
  the base object.

### Mechanism
Introduce a thread-scoped **`BranchContext`** (sibling of the existing
`ThreadLocalMultiVariantContext`) that a caller enters to "see" a branch. It carries three things,
resolved together:
1. the `OverlayNetworkIndex` (structure membership — already built),
2. a **terminal-attachment override table**: `terminal → (voltageLevel', bus'/node')` for the
   bounded set of rebound terminals,
3. the variant index (state — phase 5, ties to the columnar work).

Then:
- **Forward** — `AbstractTerminal.getVoltageLevel()` / `getConnectableBus()` consult the ambient
  `BranchContext`'s override table *only if this terminal is flagged as having an override* (a single
  boolean on the terminal keeps the common, non-rebound path a direct field read — no hot-path cost).
  In the branch, `M`'s near terminal resolves to `VLB'`/`busX'`; in the base (no branch context) it
  resolves to the direct field `VLB`/`busX`.
- **Reverse** — the materialised branch VL `VLB'` keeps a small **branch-attached-terminals** set.
  `VLB'.getTerminals()` = its own graph terminals ∪ branch-attached (`M`'s near terminal). Base `VLB`
  is shadowed in the branch (nobody enumerates it there) and keeps `M` in its own graph for the base
  view — so `M` is never physically removed from the base, and is never double-counted in the branch.
- **Far side untouched** — `M`'s far terminal keeps pointing at `VLC`; `VLC` is never materialised.
  **No cascade.**

### Why this is bounded
Only the near terminals of through-lines incident to the split VL are rebound (typically 1–4). The
far VLs, their graphs, and the rest of the network are shared by reference and never copied — the
same "dirty region ≈ 2 VLs" the envelope measured, now correct in the presence of through-lines.

## 6. Cross-cutting concerns
- **Connection state** (`connected`) is already variant-columnar and orthogonal — untouched.
- **Calculated-bus / bus-view caches** are per-topology-model; the branch's `VLB'` has its own, and
  `VLC`'s cache is unaffected because its graph didn't change. Cache invalidation stays local.
- **Traversal** (`AbstractTopologyModel.getNextTerminals` follows `branch.getTerminal1/2`): it walks
  terminal→VL, which now resolves through the context — so a topology traverse in the branch crosses
  `VLB' → M → VLC` correctly.
- **Serialization** of a branch: out of scope for the core; flatten-on-write (materialise to a plain
  network at export) is the cheap first answer.
- **Performance:** the per-terminal override flag keeps every non-rebound terminal on the current
  direct-pointer path; only rebound terminals pay a context lookup. No network-wide penalty (this is
  why B beats A).
- **Thread-safety:** `BranchContext` is thread-scoped exactly like the variant context; a branch is
  entered on a thread, mirroring the established `VariantManager` threading contract.

## 7. Phased plan + de-risking spike

**De-risking spike (do first).** Network `VLA --L-- VLB --M-- VLC`. Branch it, materialise `VLB→VLB'`,
and **branch-rebind `M`'s VLB-side terminal to `VLB'`** via a branch-scoped move recorded in the
`BranchContext`. Assert, under the branch context: `M` connects `VLB' — VLC`; `VLB'.getConnectables()`
contains `M`; **`VLC` is not materialised** and still enumerates `M`; and the base is unchanged
(`M` connects `VLB — VLC`). This is exactly the case `BranchLineSplit` rejects today, on the smallest
graph that exhibits the cascade — it validates the one load-bearing idea (forward+reverse attachment
override with no far-side touch) for ~1–2 days before committing.

**Phases.**
1. `BranchContext` + forward resolution (`getVoltageLevel`/`getConnectableBus` consult overrides;
   per-terminal fast-path flag).
2. Reverse resolution (materialised-VL branch-attached-terminals set; `getTerminals` union).
3. Branch-scoped `move`; wire into `BranchLineSplit` and delete the through-connectable guard for the
   handled (bus-breaker) case; extend `StructuralBranchLineSplitTest` to the `--M--VLC` topology.
4. Node-breaker (`NodeTerminal`/`NodeBreakerTopologyModel`): same override, node instead of bus.
5. Per-branch **state** column: fold the variant index into `BranchContext` so a branch also carries
   its own operating point for shared objects — the marriage with the columnar variant work.

## 7b. De-risking spike — RESULT (done)

Implemented and green (`StructuralBranchCascadeSpikeTest`), on exactly `VLA --L-- VLB --M-- VLC`:
- `BranchContext` (per-terminal voltage-level override + per-VL branch-attached set) and
  `ThreadLocalBranchContext` (thread-scoped active context, sibling of the variant context);
- a **guarded** hook in `AbstractTerminal.getVoltageLevel()` — a per-terminal `branchAttachmentOverride`
  flag (default false) gates a lookup of the active `BranchContext`; when no terminal is rebound and no
  context is active (all normal use) the path is the unchanged direct field read.

The test materialises `VLB → VLB'`, rebinds `M`'s near (VLB-side) terminal onto `VLB'`, and asserts:
- **forward** through the public `Terminal.getVoltageLevel()` — under the branch context `M`'s near
  terminal resolves to `VLB'`; outside it, to the base `VLB` (same shared `M` object, two views);
- **reverse (near)** — `VLB'` gains `M` via the branch-attached set;
- **no cascade** — `VLC` is the shared base instance (`assertSame`), never materialised, and still
  enumerates `M`;
- **base intact** — `M` still connects `VLB -- VLC` and base `VLB` still hosts `M`.

Full `iidm-impl` suite (1011 tests) still passes: the hot-path change is transparent. The one
load-bearing uncertainty — near-only rebind with no far-side touch and no cascade — is **retired**.

**Reverse now wired through the public API (phase 2 done).** `AbstractTopologyModel`'s four
connectable-enumeration methods route through a gated wrapper: a per-topology-model
`branchAttachmentHint` (default false → the wrapper is exactly `getTerminals()`, so every normal
voltage level is unchanged) unions in the active `BranchContext`'s branch-attached terminals for that
voltage level. The spike now asserts, through the public `VoltageLevel.getConnectables()`: under the
branch context `VLB'` hosts `M`; outside it, `VLB'` does not claim `M` — consistent with the forward
direction (`M` at `VLB'` in-branch, `VLB` in-base). Full suite still 1011 green.

**Node-breaker done (phase 4, attachment level).** The forward hook is terminal-type-agnostic and the
reverse union lives in `AbstractTopologyModel` (shared by both models), so the VL-level branch-scoped
attachment works unchanged for node/breaker. The node/breaker-specific piece — the terminal's **node**
within `VLB'` — is now covered too: `BranchContext.rebind(terminal, VLB', node)` records a node
override, and the public `Terminal.NodeBreakerView.getNode()` resolves it through the same guarded
path (`AbstractTerminal.resolveBranchNode`). The internal `NodeTerminal.getNode()` (graph-vertex
identity in the object's own base graph) deliberately stays raw. `StructuralBranchCascadeSpikeTest`
proves, on a node/breaker `VLA--L--VLB--M--VLC`: forward VL *and node* (`M` at `VLB'` node 5 in-branch,
`VLB` node 2 in-base), reverse via `getConnectables()`, no cascade, base intact.

**Calculated-bus folding done (bus/breaker).** A rebound terminal now participates in the branch
voltage level's bus computation, not just enumeration: `MergedBus`'s connected-terminal methods union
in the branch-attached terminals whose configured bus is in the merged set (`BranchContext.
branchAttachedConnectedTerminals`), gated on an active context so normal bus views are unchanged.
`StructuralBranchCascadeSpikeTest` proves it: under the branch context `VLB'`'s BusView bus lists both
its own load and the rebound `M`; outside it, only the load. Full `iidm-impl` suite (1013 tests) passes.

**Terminal→bus direction done (bus/breaker).** `AbstractTerminal.getVoltageLevel()` is refactored onto
a `resolveVoltageLevel()` helper (the branch-override logic without the removed check), and
`BusTerminal.getTopologyModel()` now resolves through it instead of the raw `voltageLevel` field — so a
rebound terminal's bus resolution (`getBusView().getBus()`, `getConnectableBus()`, connect/traverse)
follows the active context to the branch voltage level. Non-rebound terminals are unchanged (flag off →
raw field). The spike now asserts both directions agree: under the branch context `M`'s near
terminal's bus-view bus *is* `VLB'`'s bus (same id as the bus that folds `M` in). Full `iidm-impl`
suite (1013 tests) passes.

**Node/breaker bus view done — bus view now complete for both topology kinds.** The same two folds are
in place for node/breaker: `CalculatedBusImpl`'s connected-terminal methods union in the branch-attached
terminals whose branch node is in the bus's node set (`BranchContext.branchAttachedTerminalsOnNodes`),
and `NodeTerminal.getTopologyModel()`/bus-view `getBus()` resolve through `resolveVoltageLevel()` and
`resolveBranchNode(node)` so a rebound terminal's bus follows the branch context. The spike proves it on
a node/breaker `VLB'` (busbar node 0, feeder node 5): under the context `VLB'`'s calculated bus folds in
`M` and `M`'s bus-view bus is that same bus. Full `iidm-impl` suite (1013 tests) passes.

Remaining before production: branch-scoped `move` (record the rebind through the existing move API) +
removing the `BranchLineSplit` guard to wire the split end-to-end through a branch; the branch-access
contract (traversal of branch objects must run within `ThreadLocalBranchContext.run`); and a per-branch
state column (fold the variant index into `BranchContext`).

## 8. Risks & open questions
- **Reverse enumeration completeness:** every path that lists a VL's terminals/connectables must go
  through the union (audit `getTerminals`/`getConnectableStream`/bus views). A missed path = a branch
  that under-counts. Bounded and greppable, but must be exhaustive.
- **Calculated-bus correctness** when a branch-attached terminal joins `VLB'`'s bus calculation —
  needs the branch-attached set folded into `CalculatedBusTopology`.
- **`move()` semantics reuse:** confirm the existing move validation (voltage compatibility, etc.)
  is what we want for a branch rebind, or fork a leaner branch-move.
- **Multiple branches / nesting** sharing one base concurrently: the override table is per-branch in
  the context, so independent by construction — but wants an explicit test.
- **Cost of the ambient lookup** on `getVoltageLevel()` for rebound terminals in tight loops; measure
  before generalising beyond the split use case.

## Bottom line
The cascade is the index overlay and the topology graph disagreeing about where shared lines are.
Rebinding only the *near* terminal of each through-line at the split VL — forward via an ambient
`BranchContext`, reverse via a materialised-VL attached-set — makes them agree without touching the
far side, so nothing cascades. It reuses the per-variant-attachment and `move()` machinery already in
the model, and the same context is the natural home for the per-branch state column that unifies this
with the columnar variant work.
