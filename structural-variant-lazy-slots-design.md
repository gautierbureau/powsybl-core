# Design: self-sizing per-variant slots (option D)

Design pass for the last open hazard on the structural-variant stack — the one blocking
`preAllocateVariants` from becoming a performance hint instead of a hard capacity contract.

## The hazard

An object sizes its per-variant state from `variantArraySize` in its constructor, and publishes
itself in the network index as a separate, later step. A clone that runs entirely between those two
points grows every object in its stateful-objects snapshot — which cannot contain the new object,
since it is not published yet — and leaves that object one slot short. A later read of the new
variant on that object goes out of bounds.

It is unreachable today only because the overflow path throws rather than growing while multi-thread
access is enabled. Removing that throw makes it live, so it has to be closed in the same change.

Two coordination-based fixes were considered and rejected:

- **Lock around publication.** Does not work: every adder calls `topologyModel.attach(terminal, …)`
  *before* `index.checkAndAdd(…)`, so the object is reachable through voltage-level topology a step
  before publication. In node-breaker the terminal becomes a shared graph vertex object, visible to
  any variant immediately; in bus-breaker, an object created in the base variant is copied into a
  new variant along with the base's terminal list. The window that matters opens at `attach`, which
  is in every adder rather than behind a choke point.
- **Repair pass after the clone's growth.** Symmetric hole: a creator that read the old
  `variantArraySize`, then published after the repair pass finished, is short and was in no snapshot.

Both try to coordinate creation against cloning. D removes the need to.

## The idea

Make a missing slot impossible to observe: `get(variant)` materializes the slot on demand instead of
relying on the extend cascade having reached this object. "Short" stops being a state a reader can
see, so no ordering between creation and cloning has to be established.

## The inheritance rule, and why it is sound

The question that decides whether D is correct: **what value does a materialized slot hold?**

Inheriting from the parent variant (via `cowState.getParent`) is wrong. By the time the slot is
materialized, the parent may have been written since the fork, and copying it then would leak
post-fork divergence into a variant that should not see it.

The correct rule is: **a materialized slot holds the object's construction-time initial value.**

The justification is an invariant about when slots go missing:

> A slot for variant *m* is missing only if variant *m* was created by a clone whose stateful-objects
> snapshot predated this object's publication — that is, only if the object did not yet exist, as far
> as the network was concerned, when variant *m* was forked.

Once an object is published it is in every later snapshot, so every later clone extends it. Therefore
a missing slot always corresponds to a fork at which the object was newborn. There is no "value at
fork time" to inherit, and the only defensible value is the one the object was born with — which is
exactly what every slot held at construction.

This yields the property that makes D safe:

> **Materialization is time-invariant and idempotent.** The value produced does not depend on when
> materialization happens, so a lazily materialized slot and an eagerly extended one are
> indistinguishable. Lazy sizing therefore cannot violate snapshot semantics.

Note this is about slots the cascade *missed*. Objects present at clone time still go through the
eager cascade and inherit from the source variant as they do today — that path is unchanged, and
keeping it means no clone-path regression.

## Mechanism

`null` is a legitimate stored value in both current users (`unsetConnectableBusId` stores null; the
node-breaker fictitious maps start null), so absence cannot be encoded as null. A private sentinel is
required:

- Chunk slots are initialised to `UNSET` — a cheap fill, no supplier calls.
- `get(v)`:
  1. read the spine (one volatile read);
  2. if `v`'s chunk index is beyond the spine, materialize-and-grow;
  3. read the slot; if it is `UNSET`, materialize;
  4. otherwise return it.
- `materialize(v)`: `synchronized`, re-check, store `initial.get()`, return it.
- `set(v, value)` overwrites `UNSET` normally. `shrink`/`clear` restore `UNSET` rather than null, so a
  recycled index re-materializes if read before it is re-allocated.

Growth still only ever *appends* chunks, so an existing chunk is never moved or copied — the property
that already makes concurrent writes safe. Lazy growth must be `synchronized`, though: two threads
growing concurrently could otherwise publish two different chunk objects for the same index, and a
write into one would be invisible through the other. That breaks the stability invariant the whole
design rests on. Reads stay lock-free; growth is rare and off the hot path.

The array must retain a `Supplier<T>` for the initial value — a new per-instance field. For
`ConfiguredBusImpl.terminals` the supplier is non-capturing (`ArrayList::new`). For
`BusTerminal.connectableBusId` it captures the id, converting a lambda that is currently allocated
and discarded during construction into a retained one: roughly 16 bytes per terminal.

## Read-path cost

Against today's `chunks[v >>> SHIFT][v & MASK]`, D adds a length compare and an identity compare.
Both branches are perfectly predicted in steady state, so the expected cost is well under a
nanosecond — against the 26.7 ns plain / 66.7 ns thread-local `getP()` baseline measured for the
columnar work, that is in the noise. It should still be measured rather than assumed.

`VariantArray` (used by `NetworkImpl` and all three topology models) needs the same treatment and is
hotter, since topology models read it on every calculated-bus access. Its current `get()` goes
through `List.get`; converting it to the same scheme should be neutral or slightly better.

## Scope

In scope — per-object per-variant state not held in a columnar store:

- `VariantRefArray`: `BusTerminal.connectableBusId`, `ConfiguredBusImpl.terminals`,
  `NodeBreakerTopologyModel`'s fictitious P0/Q0 maps.
- `VariantArray`: `NetworkImpl`, `BusBreakerTopologyModel`, `NodeBreakerTopologyModel`,
  `DcTopologyModel`. Topology models are genuinely in scope: `VoltageLevelAdderImpl` rejects adding a
  voltage level only to a **shared** substation, so one created in the same variant accepts new
  voltage levels, and a topology model can therefore be constructed during a parallel region.

Out of scope — already safe:

- The three columnar stores. A row allocated by `allocateRow` is initialised in every live variant
  band, and the store grows its own variant dimension by copying only the spine, so a newly created
  object's columnar state is correct for every variant regardless of the cascade.
- `VariantScopedExistence` / `VariantScopedMembership`: network-level, sized under the variant lock.

## What D does not do

- It does not remove the eager cascade, which stays as the fast path for objects present at clone time.
- It does not address visibility — whether an object *should* be seen in a given variant is
  `VariantScopedExistence`'s business, and is orthogonal. D only guarantees that a read which does
  happen cannot go out of bounds or observe a torn slot.
- It does not by itself flip the overflow path. That is a separate, small change to
  `VariantManagerImpl`, which D unblocks.

## Verification plan

The soak that has been used so far would **not** exercise this: with capacity reserved up front, the
parallel region never grows, so the race never arises. Order matters here — flipping the overflow
path is what puts the change under test, so it should land in the same series, not later.

1. Unit tests on `VariantRefArray` and `VariantArray` for the lazy path directly: materialize beyond
   the current size, materialize across a chunk boundary, materialize a slot whose legitimate value is
   null, concurrent materialization of the same slot from several threads, and materialize-then-set
   ordering.
2. A test asserting the time-invariance property: a slot read early and a slot read late yield the
   same value, and both match what the eager cascade would have produced.
3. Flip the overflow path, then soak a workload where workers **both** clone and create — the current
   concurrency test creates equipment but reserves capacity, so it needs a no-pre-allocation variant.
4. Full reactor, plus a re-run of the columnar read benchmark to confirm the added compares are in
   the noise.

## Residual risks

- **Sentinel leakage.** Any code reading the backing arrays outside `get` would see `UNSET`. Contained
  by keeping both classes small and their arrays private, but it is the failure mode to watch in review.
- **The invariant in "The inheritance rule" is the load-bearing claim.** If there is any path that
  publishes an object into the index *without* it entering the stateful-objects list, an object could
  be missed by a clone that runs long after publication — and then the initial-value rule would be
  wrong, because the object would have a real fork-time value. `getStatefulObjects` derives from
  `objectsById` plus `extraObjectsById`, and `putObject`/`addExtra` are reached only from
  `doCheckAndAdd`, so the invariant holds today; it should be stated as a comment where the cache is
  built, so a future change does not silently break it.
- **Lazily created `VariantArray` elements** for topology models start with empty calculated-bus
  caches, which is semantically fine but means a first read in such a variant pays a recompute.
