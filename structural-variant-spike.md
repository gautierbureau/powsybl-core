# Structural-variant spike — copy-on-write network branching

## Problem

An IIDM **variant** isolates per-variant *state* (setpoints, tap positions, switch/terminal status)
over a **single shared structure**. It cannot isolate a *structural* change — object creation/removal
or connectivity edits — because structure is one shared graph seen by every variant.

The motivating change: **split a line and insert a voltage level at the middle of it**. Splitting a
line (remove L, add a fictitious VL + two half-lines L1/L2) is structural, so today the only way to
hold such a configuration in isolation from a reference network is a **full `Network` copy**.

## Why it's worth solving — measured saving envelope

`bench/FaultOnLineBench` measures, per configuration, what a study pays today (full copy + the split
modification) versus what a copy-on-write branch would pay (the modification + a tiny overlay):

| network | VLs | full `NetworkSerDe.copy` | the split modification | dirty region |
|---|---|---|---|---|
| case6515rte | 5 343 | **596 ms, 47.3 MB** | **< 1 ms** | 2 VLs of 5 343 |
| case13659pegase | 8 354 | **953 ms, 87.6 MB** | **< 1 ms** | 2 VLs of 8 354 |

The structural change is sub-millisecond and touches ~2 voltage levels (the two endpoints) plus the
fictitious VL and two half-lines; **everything else in the network is untouched**. The full copy is
essentially 100 % waste for this use case, and the waste grows with network size (~4000× the dirty
region on PEGASE). A branch removes the copy: ~10³× less time and heap per configuration, and
branches can run in parallel over one shared immutable base.

## The idea

A structural branch shares the parent's whole object registry by reference and records only a small
**delta** on top — *additions* (the fictitious VL, the two half-lines) and *tombstones* (the original
line). Reads merge the delta over the base; the base is never mutated, so the parent and any sibling
branch are unaffected. Memory is O(delta), not O(network).

This composes with the columnar variant work: because state is already variant-columned, a shared
object serves the parent and the branch simultaneously through its state column — no copy until the
structure actually diverges. A structural branch is, in effect, a variant whose structural delta is
allowed to be non-empty (today it is forced empty).

## What this spike proves — `OverlayNetworkIndex`

`iidm-impl/.../OverlayNetworkIndex` is a read-mostly overlay over a shared, read-only `NetworkIndex`,
mirroring the read surface `NetworkImpl` uses (`get`, `get(id, class)`, `contains`, `getAll`,
`getAll(class)`) plus the two structural mutations a branch performs (`add`, `tombstone`).

`OverlayNetworkIndexTest` (4 tests, green) demonstrates the "split a line, insert a mid-line VL"
change at the registry level on a real network (`EurostagTutorialExample1`):

- a pristine overlay is a transparent view of the base (same instances, same counts);
- applying the split to the **branch** — tombstone the original line, add the fictitious VL and the
  two half-lines — makes the branch reflect the split (`getAll(LineImpl)` now has L1+L2, not L)
  while the **base is byte-for-byte untouched** (still has the original line, has no fictitious VL);
- two branches over the same base are independent;
- the branch footprint is exactly the delta (1 tombstone + 3 additions), independent of network size.

## Status / next phases

- **Phase 1 (done):** measured envelope + `OverlayNetworkIndex` registry-level proof.
- **Phase 2a (done):** the overlay is now a drop-in `NetworkIndex` subclass, so `NetworkImpl`'s ~50
  `index.xxx` call sites work against it unchanged. `NetworkImpl.createStructuralBranch(base, id)`
  builds a branch whose index is an `OverlayNetworkIndex` over the base's index. The branch is a
  **fully traversable `Network`**: `NetworkImplStructuralBranchTest` drives it through the public
  `Network` API and shows reads fall through to the shared base (same counts, same instances),
  structural additions via the branch's own adders (`newSubstation`/`newVoltageLevel`) are isolated
  to the branch, a tombstoned base object disappears from the branch's public-API views, and the base
  is never mutated. The full `iidm-impl` suite (1005 tests) still passes — the constructor change
  (inject the index; the no-arg path delegates to `new NetworkIndex()`) is behaviour-preserving.
- **Phase 2b (in progress) — the write path.** The object model has no interception seam: a shared
  base object's `remove()`/mutators run against *its* owning network (the base) via its `ref`. So the
  irreducible primitive is **copy-on-write replacement** — install a branch-local copy that shadows
  the base object under the same id. Done in this step:
  - overlay lookups reordered so a branch-local object *shadows* the base (added → tombstone → base),
    making replacement, not just addition/removal, first-class;
  - `OverlayNetworkIndex.replace(baseObj, branchCopy)` installs a same-id copy visible only in the
    branch; `NetworkImplStructuralBranchTest` shows through the public API that the branch resolves
    the id to its copy while the base keeps the original, counts unchanged, base untouched.
  Then, **materialize mode**: while set, the branch's own adders may reconstruct objects that carry
  base ids, shadowing the base (`checkAndAdd`/`contains` cooperate so the standard adder
  uniqueness check passes). This lets the branch rebuild the *dirty region* (the endpoint voltage
  levels + the line) as branch-owned copies, after which the write path runs against those copies —
  `line.remove()` and `newLine()` hit the branch, not the base. Removing a materialised object leaves
  a tombstone so the shadowed base object stays hidden.

  **End to end (`StructuralBranchLineSplitTest`, green):** on `VLA --L-- VLB`, branch it, materialise
  the dirty region, then split the line on the branch with the ordinary public API — remove the line,
  add a fictitious mid VL and two half-lines. The branch reflects the split (`L` gone, `L1`/`L2`/`Vf`
  present, `getLineCount()==2`) while the base is byte-for-byte unchanged (still `VLA --L-- VLB`).
  This is the exact sequence `ConnectVoltageLevelOnLine` performs; it is mirrored in the test because
  that modification lives in a downstream module (iidm-modification). Full `iidm-impl` suite (1007
  tests) passes.
- **Phase 3 (in progress) — a reusable `splitLine` API + injection re-homing.** `BranchLineSplit.split(...)`
  turns the manual dance into one call: create the branch, materialise both endpoint VLs (buses,
  switches, and **re-home their injections** into the branch copies), tombstone the original line, add
  the fictitious mid VL and the two impedance-split half-lines. `StructuralBranchLineSplitTest` proves
  it on an endpoint that also hosts a **load**: the branch gets the split with the load preserved on
  the materialised endpoint (`R` split 40/60) while the base — line, load and all — is untouched.
  - The one honest boundary is made **loud, not silent**: an endpoint hosting another
    *through-connectable* (a second line/transformer, e.g. `VLA --L-- VLB --M-- VLC`) throws, because
    materialising that VL would cascade into its far VL. A test asserts the rejection. This is the
    general reference-rebinding problem the design flagged as the multi-week core.
  Full `iidm-impl` suite (1009 tests) passes.
  - *Next:* bound the through-connectable cascade (rebind a shared branch's endpoint, or materialise to
    a fixed depth); a per-branch state column (marry to the columnar variant work) so branches carry
    their own operating point; then drive the literal `ConnectVoltageLevelOnLine` from a module that
    can see both it and the branch factory.
- **Phase 3:** per-branch state column (marry to the columnar variant work), extensions/listeners on
  copied objects, branch disposal in O(delta), serialization.
