# Generic structural branching — eliminating per-type materialisation (design rework)

How to make the spike handle **all** equipment types generically, the way powsybl-network-store does —
without a per-type copy helper for every equipment. This is a proposed **v2** of the branch model; the
current spike (v1) works but pays per-type code in materialisation.

## Where the per-type code actually is

Measured on the branch: the overlay, terminal attachment, bus-view folds, and per-branch state are
**fully type-agnostic** (0 equipment-type references in `OverlayNetworkIndex`, `BranchContext`,
`ThreadLocalBranchContext`). Per-type code exists in **exactly three** classes — `BranchLineSplit`,
`BranchFlattener`, `BranchExtensionCopier` — and all of it is **materialisation copy** (`copyLoad`,
`copyGenerator`, `copyLine`, `copyTopology`, extension copy).

Root cause: v1 **deep-copies the two endpoint voltage levels** into the branch (so the new half-lines
attach to branch-owned buses without mutating the base). Deep-copying an object graph is inherently
per-type — every equipment's fields, limits, extensions. That single design choice is the *only*
source of per-type code.

network-store is generic for the mirror-image reason: it **never deep-copies objects**. Every equipment
is a uniform `Resource<Attributes>` record; partial/full resolution is get/put/tombstone on opaque
records; the only per-type knowledge is the attribute POJOs. Genericity comes from *not copying*.

## The insight: don't copy the endpoint VLs at all

We already built branch-scoped terminal **membership**: a VL's `getConnectables`/bus view unions
branch-attached terminals from the context, and a terminal's VL/node resolves through the context —
all **without touching the base VL's graph**. That was built to *rebind a shared terminal onto a branch
VL*. The same machinery, used the other way, removes the need to copy anything:

> Keep the endpoint VLs **shared**. Represent the split as a **terminal-membership delta** over those
> shared VLs — hide the split line's terminals, show the half-lines' terminals — plus a few new
> branch-owned objects (the fictitious VL and the two half-lines). Copy nothing.

Because the endpoint VLs are never materialised, their loads, generators, transformers, extensions —
anything they host — are **never touched**. The split works for an endpoint hosting *any* equipment,
with no per-type code, because that equipment simply stays on the shared VL.

This also **dissolves the cascade**: v1 needed branch-scoped rebinding of through-lines only because it
materialised the endpoint VL and had to move them off it. If the endpoint VL stays shared, a
through-line stays on it, untouched — no rebind, no cascade.

## Two symmetric primitives (one built, one new)

A VL's branch view of its terminals = base graph terminals **− branch-detached + branch-attached**:

1. **branch-attached** (built): a terminal shown on a VL in the branch that isn't in its base graph —
   already unioned in `getConnectables`/`getConnectableStream` and the bus view
   (`MergedBus`/`CalculatedBusImpl`). Used today for rebinds; reused here for the half-lines.
2. **branch-detached** (new, symmetric): a base-graph terminal **hidden** from a VL in the branch —
   subtracted in the same enumeration and bus-view paths. Needed to hide the split line's terminals
   from the shared endpoint VLs without physically removing them from the base graph. (v1 sidestepped
   this because the copied VL simply didn't include the split line; the shared-VL model needs it.)

Both live in `BranchContext` as per-VL sets and are applied in the *same* folds we already wrote — one
extra `removeIf(detached::contains)` alongside the existing `addAll(attached)`.

## The one enabling mechanism: branch-scoped connectable add

Adding a branch-owned connectable (a half-line) whose terminal must sit on a **shared** VL without
mutating that VL's graph. Today the adder registers the terminal in the target VL's topology graph.
The new path — mirroring the overlay's existing *materialize mode* — is a **branch-attach mode** on the
adder/topology model: the just-created branch-owned terminal is recorded as branch-attached in the
`BranchContext` instead of entering the base VL's graph. It is generic (it is pure terminal membership;
it does not care what connectable it is).

## Generic serialization too

v1's `BranchFlattener` is per-type copy. The generic replacement is the one we already **validated as
the oracle**: `flatten = NetworkSerDe.copy(base)` (generic over every type + extensions, maintained by
IIDM) **+ replay the structural delta** (remove the split line; add the fictitious VL and the two
half-lines) on the copy via the public API. The delta operations are few and type-agnostic. We already
proved the branch view is byte-identical to `copy + modify`, so this flatten is correct by construction
and carries zero per-type code.

## Result

| concern | v1 (current spike) | v2 (this rework) |
|---|---|---|
| endpoint VLs | deep-copied (per-type) | shared (no copy) |
| endpoint injections/extensions | re-homed per type | untouched (stay on shared VL) |
| through-lines (cascade) | rebound via context | untouched (stay on shared VL) |
| branch view of a VL | graph + attached | graph − detached + attached |
| new objects (Vf, L1, L2) | branch-owned | branch-owned (unchanged) |
| flatten / serialize | per-type `BranchFlattener` | `NetworkSerDe.copy(base)` + replay delta |
| **per-type code** | 3 classes | **none** |

Per-type knowledge collapses onto what IIDM already owns: equipment **adders** (to create the few new
branch objects) and **NetworkSerDe** (to flatten). Our branch code becomes 100% type-agnostic — the
network-store property, achieved in-place by reusing the overlay + terminal-membership machinery
instead of switching to a record model.

## Migration & de-risking

- The v1 machinery stays: overlay index, forward/reverse attachment, bus-view folds, per-branch state.
- Add: `branch-detached` set + its subtraction in the folds (small, symmetric to what exists); the
  adder **branch-attach mode**; rewrite `BranchLineSplit` to the shared-VL model; replace
  `BranchFlattener` with copy-then-replay.
- **De-risking prototype (recommended first):** the branch-detached fold + branch-attach add, on the
  existing `VLA--L--VLB--M--VLC`, splitting `L` with **no** materialisation — assert the branch shows
  `VLA[busA]→L1`, `VLB[busB]→L2`, `M` still on the shared `VLB`, base untouched, and that the endpoint
  VLs and their contents were never copied. That retires the one new primitive (branch-detached +
  branch-scoped add) before rewriting the operation.

## Honest trade-off

v2 is architecturally cleaner and generic, but it makes a branch's correctness depend *entirely* on the
ambient `BranchContext` (a VL's very terminal set is now context-dependent), so **all** branch access
must run within `ThreadLocalBranchContext.run` — a stricter contract than v1, where materialised objects
were self-consistent without a context. That is the price of never copying: the same price network-store
pays by resolving every read against the variant. It is the right trade for a general feature.

## Lineage: each v2 element vs its network-store `PartialVariantUtils` counterpart

v2 was not derived top-down from network-store, but every one of its moving parts turns out to be the
in-place analogue of a specific `PartialVariantUtils` operation. The mapping is exact enough to be worth
stating element by element — it is the strongest evidence that v2 is the *right* generic shape, because
it independently re-derives production code's algorithm without adopting its record model.

| v2 element (this design) | network-store `PartialVariantUtils` counterpart | why they coincide |
|---|---|---|
| `OverlayNetworkIndex.get(id)` — order added → tombstone → base | `getOptionalIdentifiable` — partial variant → (tombstoned? empty) → full variant | single-id read resolved by fall-through; a local override wins, an explicit tombstone erases, else inherit |
| `OverlayNetworkIndex.getAll()` — base − shadowed + added | `getIdentifiables` — `full − (partialIds ∪ tombstonedIds) + partial` | collection read = inherited set minus everything locally shadowed/removed, plus everything locally added |
| `createStructuralBranch(base, id)` — new overlay + `BranchContext`, **no element copy** | clone-from-FULL: new `variantNum` whose `fullVariantNum` points at the source, copying **no** resources | branching is O(1): record the parent pointer, copy nothing; divergence is paid lazily per element |
| v2 **branch-detached** per-VL terminal set (hide a base terminal from a shared VL) | per-table **tombstone** sets (external attributes, limits groups, regulating points) | "this inherited thing is gone in my variant" expressed as a tombstone at the *membership* granularity, not by mutating the parent |
| v2 **branch-attached** per-VL terminal set (show a new terminal on a shared VL) | `partial` resources carrying the child `variantNum` | "this thing exists only in my variant" — a local addition surfaced at read time |
| v2 flatten = `NetworkSerDe.copy(base)` + replay delta | read-time merge (`getIdentifiables`) that a full-variant clone would materialise | flatten is just the merge run eagerly and written out; the branch view is byte-identical to it by construction |
| extensions on branch objects (future) | **external attributes** with their own tombstone + merge tables | extensions resolve exactly like top-level resources: own local set, own tombstones, same fall-through |

The one structural difference: network-store stamps a `variantNum` on **every resource** and resolves
**every** read against it, so *existence itself is variant-scoped* uniformly. v2 keeps object existence
global and layers a *membership* delta (attached/detached terminals) over shared VLs. v2 is the same
algorithm applied only where the split actually perturbs the graph; network-store is the same algorithm
applied everywhere, unconditionally.

## v2.1 — variant-scoped existence (the faithful, deeper option)

v2 stops one step short of full network-store fidelity. The remaining step, **v2.1**, is to stop treating
object existence as global and make it **variant-scoped**, unifying the structural delta with IIDM's
existing per-variant *state* mechanism — i.e. give IIDM the network-store property that *which objects
exist* is answered per variant, exactly as *what setpoint a generator holds* already is.

Concretely, v2.1 would:

- Move the overlay's added/tombstoned sets out of a side `OverlayNetworkIndex` and **into the variant
  array** already threaded through every impl object, so `network.getLine(id)` returns present-or-absent
  *as a function of the active variant index* — the same array lookup that today returns a per-variant
  tap position would return per-variant existence.
- Collapse `branch-attached` / `branch-detached` into that same per-variant existence: a terminal's
  membership in a VL becomes one more variant-indexed field, so no ambient `BranchContext` is needed —
  the active variant *is* the context. This retires v2's stricter "everything must run inside
  `ThreadLocalBranchContext.run`" contract; a variant is self-consistent on its own, as v1's materialised
  objects were.
- Make branching a variant clone (`cloneVariant`) that sets a parent pointer instead of copying the
  per-variant slots — the direct analogue of network-store's `fullVariantNum`, and O(1) like it.

**Cost — and why it is not this spike's next step.** v2.1 changes the meaning of the most fundamental
IIDM read: `network.getX(id)` becomes variant-dependent, and every `NetworkIndex` / `getConnectables` /
bus-view path must consult the active variant to decide existence. That is a core-IIDM change touching
the index, the variant manager, and every topology model — far beyond a spike, and a compatibility
question for every downstream consumer that assumes object identity is variant-independent. v2 gets the
genericity (zero per-type code) **without** that core change, by scoping the delta to terminal membership
over shared VLs. v2.1 is the honest end-state if IIDM ever wants network-store's uniform model natively;
v2 is the pragmatic reach of that model achievable inside iidm-impl today.
