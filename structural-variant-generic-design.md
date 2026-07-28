# Generic structural branching — eliminating per-type materialisation (design rework)

> **Status: consolidated onto the canonical model.** The exploratory lineage in this document — v1
> (materialised copy-on-write branch) and v2 (generic don't-copy over a side-car `OverlayNetworkIndex` +
> ambient `BranchContext`) — has been **removed from the code**. It served its purpose: proving the
> mechanism and de-risking each step. The branch now carries only the canonical end-state:
> **variant-scoped structural variants (v2.1a)** — `VariantScopedExistence` + `VariantScopedMembership` +
> `VariantScopedLineSplit`, where a structural branch *is* a cloned variant and the working variant is
> the context — plus the **copy-on-write variant-state prototype (v2.1b)**, `CowVariantColumn` /
> `CowVariantParentage`, that would make branch creation O(1). No `...V2` classes, no overlay, no
> thread-local context remain. The sections below keep the full v1→v2→v2.1 reasoning as the design record
> of *why* the canonical model is shaped the way it is.

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
- **De-risking prototype (built, validated).** The branch-detached fold + branch-attach add, on the
  existing `VLA--L--VLB--M--VLC`, splitting `L` with **no** materialisation — the branch shows
  `VLA[busA]→L1`, `VLB[busB]→L2`, `M` still on the shared `VLB`, base untouched, and the endpoint VLs
  are the **same shared objects** (`assertSame`, not copies). Implemented as `BranchLineSplitV2` +
  `BranchContext.detach`/`beginBranchAttach`/`branchAttach`, the `branch-detached` subtraction in
  `AbstractTopologyModel`'s enumeration and `MergedBus`'s bus view, and the `branchAttachIntercept` on
  `BusBreakerTopologyModel.attach`; proven by `StructuralBranchLineSplitV2Test`. This retires both new
  primitives (branch-detached + branch-scoped add) with zero per-type code, before rewriting the full
  operation.
- **Node/breaker endpoints (built, validated).** The same two primitives extended to node/breaker:
  the branch-attach intercept in `NodeBreakerTopologyModel.attach` (and its `checkTerminal` skips the
  graph-occupancy check for a branch-attach target, since the split line's terminal legitimately still
  holds the node in the shared graph), plus the `branch-detached` subtraction in `CalculatedBusImpl`'s
  calculated-bus view (`branchDetachedTerminalsOnNodes`). A half-line branch-attaches at the split
  line's freed feeder node without mutating the shared graph; the enumeration and calculated-bus folds
  show `L1`/`L2` and keep the through-line `M` on the shared VL with no rebind. Both topology kinds now
  pass `StructuralBranchLineSplitV2Test`.
- **Generic flatten (built, validated).** The last per-type class, `BranchFlattener`, is replaced by a
  flatten that carries **zero per-type code**: `BranchFlattenerV2.flatten` = a generic base copy +
  replaying the branch's recorded structural delta. The base copy is an injected
  `UnaryOperator<Network>` — in production `NetworkSerDe::copy` (all types + extensions, maintained by
  IIDM), injected because `NetworkSerDe` lives in the downstream `iidm-serde` module while `iidm-impl`
  depends only on `iidm-api`. The delta is recorded by the split operation as public-API mutations
  (`BranchContext.recordStructuralDelta`/`replayStructuralDelta`): really remove the line, really add
  the fictitious VL and half-lines. The result is a plain, self-contained network — the test asserts
  every element's parent is the flat network (the exact bug the flatten fixes) — for both topology
  kinds. Full iidm-impl suite green (1023 tests). **v2 is now complete: the split and its serialization
  carry no per-type code.**

## From spike to integration (naming is scaffolding)

The `...V2` class names (`BranchLineSplitV2`, `BranchFlattenerV2`) and the parallel-to-v1 layout are
**spike scaffolding**, kept only so v1 and v2 coexist while v2 is proven. A real integration into
powsybl would carry none of them; it folds this behaviour into IIDM's existing design rather than adding
a second lineage beside it:

- **No `...V2` classes.** The don't-copy split becomes the implementation *of* the existing
  line-splitting modification (`iidm-modification`'s `ConnectVoltageLevelOnLine` / split-line family),
  not a sibling class. `BranchLineSplit` (v1) is retired in favour of it.
- **The primitives become first-class, unconditional.** `branch-attached` / `branch-detached` terminal
  membership and the branch-attach add stop being a spike side-channel gated by `branchAttachmentHint`
  and become the sanctioned way a structural branch expresses a membership delta — reviewed as core
  topology-model API, not a hint flag.
- **Flatten wires to the real copier.** `BranchFlattenerV2`'s injected `UnaryOperator<Network>` is bound
  to `NetworkSerDe::copy` at the serialization boundary (in `iidm-serde`, which already depends on
  `iidm-impl`), so no `iidm-impl → iidm-serde` dependency is introduced; the recorded-delta replay stays
  in core.
- **The branch itself** (`OverlayNetworkIndex` + `BranchContext` + thread-local) is the piece that needs
  a deliberate home and public contract — see the v2.1 discussion for how far that unification could go.

In short: v2 proves the *mechanism* with throwaway names; integration is a rename-and-merge into the
existing modification, topology-model, and serialization surfaces, not new parallel types.

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

### Two depth tiers (be precise about how far v2.1 goes)

"Variant-scoped existence" splits into two genuinely different amounts of work; scoring them together
overstates the minimal step and understates the maximal one.

- **v2.1a — existence-only.** Make *which objects exist* variant-dependent, but leave per-field **state**
  copied eagerly per variant exactly as today. Object existence resolves at the index and at the terminal-
  membership fold sites against the active variant instead of an ambient `BranchContext`. This is the
  minimal faithful step: it retires the "everything runs inside `ThreadLocalBranchContext.run`" contract
  (a variant is self-consistent) while leaving the 57 state-array classes untouched.

  **Core claim de-risked (prototype built).** `VariantScopedExistence` (a `MultiVariantObject`) makes a
  **single** network answer `getLine(id)` / `getIdentifiable` / `contains` / the counts differently per
  **working variant** — no `OverlayNetworkIndex`, no ambient `BranchContext`; the "branch" is a cloned
  variant and the working variant *is* the selector. Critically it rides the **real** `cloneVariant`:
  it is in the index's stateful-objects list, so `VariantManagerImpl` grows and copies its hidden-id
  column via `extendVariantArraySize`/`allocateVariantArrayElement` exactly as it does tap positions —
  cloning `faulted`→`faulted2` inherits the hide, cloning the pristine `INITIAL` does not. The `NetworkIndex`
  hook is **null-gated** (an unset `existence` field ⇒ the exact former hot path), so all 1026 iidm-impl
  tests pass unchanged. Proven by `VariantScopedExistenceTest`.

  **Membership folded in too (prototype extended).** `VariantScopedMembership` (also a `MultiVariantObject`)
  moves v2's `attached`/`detached` terminal delta out of the ambient `BranchContext` and into per-variant
  state: a VL's terminal set enumerated by `getConnectables` becomes `graph − detached(activeVariant) +
  attached(activeVariant)`. The `AbstractTopologyModel` enumeration folds now read this variant-scoped
  membership **ahead of** the `BranchContext` (v2 remains the fallback when membership is not enabled), so
  the same folds v2 drove from a thread-local context now read the **working variant** — no
  `ThreadLocalBranchContext.run`. It rides the real clone identically: `VariantScopedMembershipTest`
  shows `getConnectables` moving a terminal between VLs purely by `setWorkingVariant`, and the move being
  inherited by a clone of `faulted` but not by a clone of `INITIAL`. Together, existence + membership mean
  a split can be expressed as **pure variant state on a single network** — the branch is a cloned variant,
  the working variant is the context.

  **Every read path now variant-driven.** The bus-view folds (`MergedBus` bus/breaker,
  `CalculatedBusImpl` node/breaker) read variant-scoped membership ahead of the `BranchContext` too, via
  bus/node filters on `VariantScopedMembership` that mirror the `BranchContext` ones. So enumeration
  (`getConnectables`) **and** both bus views resolve terminal membership against the working variant —
  `VariantScopedMembershipTest` proves the bus-view subtraction flips a line off a bus by
  `setWorkingVariant`, bus/breaker and node/breaker, with the initial variant untouched. (The attach
  *direction* in the bus view needs a terminal bussed on the target VL — exactly what the real split
  produces and what v2's `StructuralBranchLineSplitV2Test` already asserts through the same fold code;
  a synthetic foreign-bus terminal is correctly rejected by the bus filter, so it is left to the turnkey
  split.) Full iidm-impl suite green (1030 tests).

  **Turnkey single-network split (built, validated).** `VariantScopedLineSplit.split(n, "faulted", ...)`
  performs a fault-on-line split as **pure variant state on one network** — no `OverlayNetworkIndex`, no
  `BranchContext`, no `ThreadLocalBranchContext`. It clones the working variant into `faulted` and, in
  that variant only: hides the split line (`VariantScopedExistence.hideInCurrentVariant`), detaches its
  terminals from the shared endpoints (`VariantScopedMembership.detachInCurrentVariant`), adds the
  fictitious VL and the two half-lines, and makes those branch-owned objects exist only in `faulted`
  (`existOnlyInCurrentVariant`). The half-lines' outer terminals branch-attach onto the shared endpoints
  through the **same** topology-model intercept as v2 — now re-pointed so that, when a
  `VariantScopedMembership` attach window is open, it records into the active variant's membership instead
  of a `BranchContext`. After the call, `setWorkingVariant("faulted")` shows the whole split — enumeration
  **and** both bus views, `L1` on VLA's bus, `L2`/`M` on VLB's — while the source variant, on the same
  network, is the untouched original; `VariantScopedLineSplitTest` asserts this for bus/breaker and
  node/breaker. This is the payoff of the v2.1a direction: **the branch is a cloned variant, the working
  variant is the context, and the split touches only variant-scoped state.** Full iidm-impl suite green
  (1032 tests).

  **Type-agnostic across equipment (built, validated).** The whole point of the model is that existence
  and membership are driven by ids and terminals, never by equipment type — so it generalises to every
  connectable for free. `VariantScopedConnectableAdd.addInVariant(...)` adds *any* connectable so that it
  exists only in a given variant, with **zero per-type code**: the caller supplies the ordinary adder
  (`() -> vl.newGenerator()…add()`), and the utility clones the variant, opens a branch-attach window on
  the endpoint VL(s), runs the adder (its terminal is intercepted into the variant's membership, never the
  shared graph), and marks the new object `existOnlyInCurrentVariant`. `VariantScopedConnectableAddTest`
  proves it for a **generator** and a **load** through the exact same path — a "what-if we add a unit"
  scenario is a cloned variant; the base never sees it — and the dual, a **structural removal** ("unit
  decommissioned in this scenario": hide the object + detach its terminal in one variant), which regular
  IIDM variants cannot express. Generators were just one instance; the line split is another. Full
  iidm-impl suite green (1014 tests).

  **Measured payoff.** `VariantScopedSplitBenchmarkTest` (in `iidm-serde`, gated behind `-Dbenchmark=true`)
  compares the turnkey variant-scoped split against the classic approach — `NetworkSerDe.copy(base)` then
  split on the copy (a whole second network) — on a synthetic bus/breaker chain:

  | lines | copy+split | variant split | speed-up | copy retained | variant retained | memory |
  |---|---|---|---|---|---|---|
  | 1 000 | ~40 ms | ~2.4 ms | ~16–20× | ~5.5 MB | ~0.4 MB | ~15× |
  | 4 000 | ~70–110 ms | ~4 ms | ~17–27× | ~22 MB | ~1.2–1.8 MB | ~12–19× |
  | 10 000 | ~240–270 ms | ~13–15 ms | ~18× | ~55 MB | ~4.6 MB | ~12× |

  So a structural branch is created **~15–20× faster and holds ~12–15× less memory** than duplicating the
  network. *Honest nuance:* the variant split is **not O(1)** — `cloneVariant` extends the per-variant
  array of every stateful object, so its cost still grows with network size; the win is a far smaller
  constant (a tiny per-variant slot vs a full object copy), not a different complexity class. (Caveats:
  synthetic chain; `Runtime`-based retained-heap is approximate; `NetworkSerDe.copy` is the real
  copy-a-network cost, including serialize+deserialize. Numbers are indicative, not a benchmark contract.)
  This quantifies why the whole line of work matters: for scenario/contingency workloads that spin up many
  structural branches, not copying the network is a large, measurable saving.
- **v2.1b — full network-store parity.** Also make per-field state **lazy copy-on-write** behind a parent
  pointer (the direct `fullVariantNum` analogue): a cloned variant copies *no* slots and inherits the
  parent's until first write. This is true parity — existence *and* state resolved uniformly per variant
  with O(1) branching — but it rewrites the variant lifecycle shared by every multi-variant object.

  **Core mechanism de-risked (prototype built).** `CowVariantColumn<T>` over a `CowVariantParentage`
  stores one variant-dependent field copy-on-write: a variant holds a value only where it has *diverged*,
  else a read falls through the parent chain. This is the storage every `MultiVariantObject` would adopt.
  It settles the two hard questions:
  - **Forking is O(1) and copies nothing** — `CowVariantParentage.fork` records a parent pointer; the test
    forks 1 000 variants and the column's stored-entry count stays at **1**. This is exactly what v2.1a
    (and the benchmark) could not achieve: there `cloneVariant` extends *every* object's array, an O(N)
    copy — the source of v2.1a's ~12–15× (but still O(N)) memory. v2.1b makes branch creation independent
    of network size.
  - **IIDM snapshot semantics are preserved** — the subtle part. `cloneVariant` is a point-in-time copy, so
    a later change to a parent variant must *not* leak into a variant forked earlier. `CowVariantColumn`
    keeps this by moving the copy to the **write** side: before a variant's value diverges, the current
    value is frozen into the children that still inherit it (`CowVariantColumnTest` asserts the no-leak
    case directly). Cost therefore moves from fork (was O(objects)) to a write of a *shared* variant (now
    O(inheriting children), per column) — the right trade when branches are created far more often than a
    base variant is mutated after forking.

  *Scope of the prototype:* it proves the CoW column algorithm — O(1) fork, correct snapshot semantics,
  storage O(divergences) not O(variants). The full v2.1b is still the large change scored below: swapping
  every one of the 57 `MultiVariantObject` classes' dense per-variant arrays for this column and making
  `VariantManagerImpl.cloneVariant` fork the parentage instead of allocating slots. The prototype retires
  the design risk of that change; it does not perform it. Full iidm-impl suite green (1037 tests).

### Grounded blast radius (measured on this tree)

| surface | v1 / v2 | v2.1a (existence-only) | v2.1b (full parity) |
|---|---|---|---|
| `NetworkIndex` existence reads (`get`, `getAll`) | side-car overlay, base untouched | **2 methods** become variant-aware | 2 methods, variant-aware |
| variant lifecycle (`VariantManagerImpl`, `VariantContext`) | unchanged | **clone** sets an existence parent link | clone sets parent link **for state too** |
| existence-deciding read paths (`getConnectables` ×12, bus-view/topology folds ×6) | context-gated union | **~18 sites** consult active variant | ~18 sites consult active variant |
| per-field variant arrays (`MultiVariantObject` allocate/extend/reduce/delete) | eager, 1 slot/variant — **unchanged** | **unchanged** (state still eager) | **all 57 classes** move to lazy inherit-then-copy |
| downstream contract: is `network.getX(id)` variant-independent? | yes | **no** | no |

### Score (1 = worst, 5 = best on each axis)

| axis | v1 | v2 | v2.1a | v2.1b |
|---|:--:|:--:|:--:|:--:|
| genericity (zero per-type code) | 2 | 5 | 5 | 5 |
| fidelity to network-store model | 2 | 4 | 4 | **5** |
| self-consistent objects (no ambient context) | **5** | 2 | **5** | **5** |
| O(1) branch creation | 3 | 4 | 4 | **5** |
| per-variant read cost | **5** | 4 | 4 | 3 |
| blast radius (higher = smaller/safer) | **5** | 4 | 2 | **1** |
| downstream compatibility (higher = safer) | **5** | **5** | 2 | 2 |
| implementation effort (higher = cheaper) | 3 | 4 | 2 | **1** |
| **fit as *this spike's* next step** | — | **5** | 3 | 1 |

**Reading the score.** v2 is the sweet spot *for the spike*: it buys the full genericity win (5) at a
small, self-contained blast radius (4) with no downstream-compatibility cost (5), paying only the stricter
ambient-context contract (self-consistency drops to 2). v2.1a is the honest way to *retire* that one weak
spot — it restores self-consistency to 5 — but only by making `network.getX(id)` variant-dependent, which
craters downstream compatibility (5 → 2) and roughly triples the blast radius; it is a core-IIDM proposal,
not a spike increment. v2.1b is the only column that reaches full network-store fidelity (5) and O(1)
everything (5), but it is the most invasive change in the table — the 57-class lifecycle rewrite — and
scores worst on effort and blast radius (1). The ordering is therefore deliberate: **v2 now; v2.1a only if
IIDM commits to variant-scoped existence as a core feature; v2.1b only if it further commits to network-
store's lazy-copy state model wholesale.**
