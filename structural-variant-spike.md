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

- **Phase 1 (this spike, done):** measured envelope + `OverlayNetworkIndex` registry-level proof.
- **Phase 2:** wire the overlay into `NetworkImpl`/`VariantContext` so a branch is a fully traversable
  `Network` (reads fall through, structural writes copy-on-write only the dirty region — the two
  endpoint VLs, whose *internal* topology is unchanged, only one terminal reference rebinds). Then a
  branch runs the existing `ConnectVoltageLevelOnLine` modification unchanged, as its own oracle
  against a full-copy-then-modify baseline.
- **Phase 3:** extensions/listeners on copied objects, branch disposal in O(delta), serialization.

The `OverlayNetworkIndex` class is not yet referenced by `NetworkImpl`; it is exercised only by its
test, by design for the spike.
