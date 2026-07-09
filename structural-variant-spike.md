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
- **Phase 2b (next):** the write path — route a base object's `remove()` and the endpoint VLs'
  structural edits through copy-on-write so the branch can run the existing
  `ConnectVoltageLevelOnLine` modification **unchanged**, as its own oracle against a
  full-copy-then-modify baseline. The hard part is owning-network back-references: a shared object's
  operations must resolve to the branch, which means COW-materialising the dirty region (the two
  endpoint VLs — internal topology unchanged, one terminal reference rebinds).
- **Phase 3:** per-branch state column (marry to the columnar variant work), extensions/listeners on
  copied objects, branch disposal in O(delta), serialization.
