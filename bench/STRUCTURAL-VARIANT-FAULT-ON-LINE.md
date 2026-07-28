# First use case: structural variant for "fault on line" (short-circuit)

## The short-circuit need

To compute a short-circuit at a point **x% along a line** (not at an existing bus), the standard
trick is to insert a *fictitious* voltage level with a single node at the fault point, splitting the
line L into two segments L1 (x% of L's impedance) and L2 ((100-x)%), then apply the fault at the new
node. A study sweeps many (line, position) pairs; each is a **distinct structural configuration** of
the same base network, and the base must stay pristine as the reference.

This is exactly the "variants can't isolate a structural split" wall: line-split + VL-creation are
structural, so today each fault point needs a **full `Network` copy**. For a sweep of, say, 9
positions × 500 lines that is 4500 deep copies of an entire network.

## Why this is the ideal *first* structural-variant use case

1. **It is local.** The change touches a bounded, known set of objects (below) — everything else in
   the network is untouched, so copy-on-write sharing pays off maximally.
2. **It already has a reference implementation** — `ConnectVoltageLevelOnLine` /
   `CreateLineOnLine` in `iidm-modification` — which is a ready-made **correctness oracle**: apply it
   to a full copy and diff against the branch.
3. **It resolves the concurrent-vs-sequential fork** toward the COW-branch design: SC fault points
   are *independent* and parallelizable, and the base must stay live and clean — apply/revert on one
   shared network can't do that, branching can.
4. **It has a numeric acceptance test**: fault current at the fault node must match between
   full-copy-then-modify and branch-then-modify, bit for bit.

## The exact object-level delta (from `ConnectVoltageLevelOnLine.apply`)

Splitting line L (between endpoint VLs `VLa` and `VLb`) at a new fictitious `VLf`:

| op | object | overlay effect |
|---|---|---|
| add | `VLf` (fictitious VL + its 1 node/bus, the fault node) | addition |
| remove | line `L` | tombstone |
| add | line `L1` (VLa↔VLf, x% impedance) | addition |
| add | line `L2` (VLf↔VLb, (100-x)% impedance) | addition |
| rebind | `VLa`: L's terminal at node k → L1's terminal at node k | **COW VLa** |
| rebind | `VLb`: L's terminal at node m → L2's terminal at node m | **COW VLb** |

Note the endpoint VLs' *internal switch topology is unchanged* — only the connectable sitting at one
node changes identity (L → L1). So even the two COW'd VLs are a near-trivial copy (rebind one
terminal reference), not a deep re-wire.

**Dirty region = { VLf, L, L1, L2, VLa, VLb }. Everything else — all other VLs, substations, lines,
their state columns — is shared by reference.**

## How it looks under the branch/overlay model

- `network.branchStructure()` → child with an empty `OverlayIndex` delta + a fresh state column.
  O(1), shares the whole graph.
- Apply the split **to the branch**: the 4 index mutations (remove L, add VLf/L1/L2) land in the
  branch's `OverlayIndex` delta; the two endpoint VLs are copy-on-write materialized into the branch
  because their terminal reference changes. Parent's `getLine(L)` still returns L; branch's does not.
- **The short-circuit engine needs no changes.** A branch *is* a `Network` (the overlay makes
  `getVoltageLevel`, `getBusView`, iteration, traversal all fall through to the shared base except
  for the delta). The engine traverses the branch and applies the fault at VLf's node exactly as if
  it were handed a full copy.

## Cost

Per (line, position) branch: ~2 voltage levels materialized + 4 index-delta entries + 1 state column
(cheap after the columnar work), versus a whole-network deep copy today. On PEGASE-13k that is
~2 of 8354 VLs instead of all of them — roughly three orders of magnitude less per fault point, and
the branches can run in parallel over the shared immutable base.

## De-risking spike (concrete, has an oracle)

1. Implement `OverlayIndex(base, delta)` and copy-on-write for the *terminal-rebind* case only
   (no bus split, no internal re-wire — the endpoint VLs keep their graph).
2. On a real network: `branch = net.branchStructure()`, then run the line-split into a fictitious VL
   on `branch`.
3. **Structural oracle**: build `full = net.copy()`, apply the *existing*
   `ConnectVoltageLevelOnLine` to `full`, and assert branch and full are view-equal (same VLs, buses,
   line impedances, terminals).
4. **Numeric oracle**: run the short-circuit fault at VLf's node on both `branch` and `full`; assert
   identical fault current.
5. **Isolation + memory oracle**: assert `net` (the base) is unchanged, and that the branch's
   retained heap ≈ two VLs, not one network.

Passing (3)+(4) proves the two load-bearing claims — overlay reads look like a real network, and a
shared object serves parent and branch through the variant column — on the smallest possible surface,
before tackling the harder internal bus/node-split COW region.

## Open questions to pin down before coding

- **Fictitious flag / cleanup**: VLf and L1/L2 should carry `fictitious=true` and never leak into the
  base; branch disposal must be O(delta).
- **Extensions**: `BranchOperationalLimitsGroupsCopy`, `ConnectablePosition` — copied onto L1/L2
  (already handled by the reference modification; the branch just runs the same code).
- **Serialization of a branch** is out of scope for the spike (SC consumes it in-memory).
- **Boundary with variants**: the branch gets one new state column; SC is a single-state computation,
  so no multi-variant interaction to resolve in v1.
