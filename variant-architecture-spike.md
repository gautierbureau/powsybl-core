# Variant-storage architectural rewrite — spike findings

Branch: `claude/iidm-variant-cow-spike`. Goal: evaluate an architectural rewrite
of per-variant state to eliminate the intrinsic per-object copy that dominates
`cloneVariant`/`removeVariant` after IIDM-F and IIDM-G.

## Background

Each variant-dependent object stores its per-variant state in its own arrays
indexed by variant index — e.g. `AbstractTerminal` holds `TDoubleArrayList p, q`;
`ConfiguredBusImpl` holds `v, angle, connectedComponentNumber, …`. A clone appends
a copy of the source slot to **every** object's arrays; a remove deletes the slot.
With ~55 000 stateful objects this is O(objects × variants) and is the top
addressable powsybl frame in the post-PROF-1 profile.

Two candidate rewrites were evaluated.

## Candidate 1 — Copy-on-write variants: **NOT VIABLE (correctness conflict)**

Idea: at clone time, share the source variant's storage and copy lazily on the
first write to the new variant (O(1) clone).

This conflicts with the documented, relied-upon concurrency contract of
`VariantManager` (`iidm-api/.../VariantManager.java:17-21`):

> The classical pattern for multi-variant processing is to **pre-allocate
> variants** and call `allowVariantMultiThreadAccess`, **work on variants from
> other threads** … and then remove variants from the main thread once work is
> over.

and (`VariantArray.java:20-25`):

> structural changes are performed on the main thread only (never concurrently
> with variant reads/writes), while different threads may read/write pre-allocated
> variants simultaneously (each thread on its own variant index).

Copy-on-write turns the **first write** to a shared variant into a *structural
change* (allocating physical storage across all objects). Under the standard
multithreaded security-analysis pattern — pre-clone N variants, hand one to each
worker thread, write them in parallel — that structural change would run on a
worker thread, concurrently with the other workers mutating the same objects'
variant arrays. That is a data race and violates "structural changes on the main
thread only."

The eager copy at clone time is therefore **load-bearing**: it is what gives each
variant independent physical storage so parallel writes are safe. To make CoW safe
you would have to eagerly materialize every clone's storage before the threads
start — which is exactly what the code already does, so there is nothing to save.

**Conclusion: copy-on-write is incompatible with the concurrency model. Rejected.**

## Candidate 2 — Columnar / structure-of-arrays: **viable, large ceiling, large rewrite**

Idea: store each variant-dependent field for all objects of a type in one shared
network-level array (e.g. `double[] terminalP` laid out variant-major). A clone
extends every field with a handful of bulk `System.arraycopy` calls instead of
55 000 per-object method calls. This keeps eager, independent per-variant storage,
so it is **thread-safe** (unlike CoW).

Ceiling measured (`cowspike/ColumnarBench.java`, 40 000 objects × 2 fields,
extend 1→51 variants, 50×):

| Layout | median |
|---|---:|
| AoS — current per-object `TDoubleArrayList` | **108.96 ms** |
| SoA — columnar `double[]` + `System.arraycopy` | **4.86 ms** |
| speedup | **22.4×** |

The real speedup is likely higher still: the microbench's AoS loop is monomorphic,
whereas the real `VariantManager` loop dispatches megamorphically over ~30 object
types × 55 000 receivers.

### Cost of the rewrite

- A network-level column store per variant-dependent field, owned by `NetworkImpl`
  and extended once per clone (main thread only).
- Every variant-dependent object must hold a **row index** and reach the store;
  every getter/setter changes from `p.get(variantIndex)` to
  `store[variantIndex][row]`.
- Terminal/bus/connectable **lifecycle** must allocate and recycle rows
  (construction is centralized in `TerminalBuilder`, but equipment removal has no
  single chokepoint today — row recycling needs one).
- The object-based `VariantArray<Variant>` path (heterogeneous per-variant objects,
  used by many extensions and complex objects) does **not** fit a primitive
  columnar layout and would stay per-object — so the win only covers the primitive
  fields.
- ~30+ impl classes touched; golden-file/round-trip tests must stay green.

This is a multi-week, high-risk rewrite.

### Working vertical slice — terminal p/q columnarized in-situ

A real end-to-end slice was built on this branch: terminal `p`/`q` moved from the
per-terminal `TDoubleArrayList` to a network-level flat columnar store
(`TerminalVariantStore`), driven once per clone by `NetworkImpl`, with terminal-row
re-homing across `merge`/`detach`.

- **Correctness: fully green** — iidm-impl 997/997, iidm-serde golden-file 303/303,
  byte-identical. Merge/detach lifecycle handled by re-homing rows between stores
  (both operations are single-variant by contract, so only band 0 is transferred).
  Row recycling is implemented: a removed terminal frees its row (guarded by the
  existing `removed` flag) for reuse by a later terminal — no leak on churn.
- **A key implementation lesson:** the store must be a **flat pre-grown `double[]`**
  (`p[variant*stride + row]`) copied with `System.arraycopy` into existing space. A
  first `double[][]` version that allocated a fresh band per clone *regressed* the
  benchmark — the per-clone garbage swamped the arraycopy win. Flat layout = no
  per-clone allocation.
- **In-situ measurement** (same container, this session's machine ~2× slower than
  the earlier runs, so compare only within this block):

  | Layout | variant-clone-remove ×50 (median) |
  |---|---:|
  | Baseline (IIDM-G, per-object trove) | ~5740 ms |
  | Columnar terminal p/q (flat store) | ~4400–5150 ms |

  → roughly **−10 % to −20 %** from columnarizing **one field-pair**.

- **Why only ~15 %, not 22×:** the 22× ceiling is the cost of the p/q *extend alone*.
  In the full operation, terminal p/q is just one of many per-variant fields —
  `NodeTerminal` still carries v / angle / connectedComponentNumber /
  synchronousComponentNumber, `ConfiguredBusImpl` its own set, plus connectables,
  tap changers, etc., all still array-of-structures. The slice removes only p/q's
  share. **This is the decisive extrapolation datum:** the big win requires
  columnarizing *every* primitive per-variant field, i.e. the full rewrite; each
  field converted buys a slice of the total like this one did.

### Real-workload relevance (the honest caveat)

`variant-clone-remove ×50` is a **synthetic** stress test. In real workloads:

- **CGMES import** — the #1 real cost — does no variant cloning at all.
- **Security analysis** clones variants but then writes them heavily (power-flow
  results into bus v/angle and terminal p/q), so the clone-*extend* is a small
  fraction of end-to-end SA time (dominated by the flow solve itself).

So even a perfect columnar rewrite (saving order ~1–2 s on the ×50 microbench)
moves real end-to-end times little. The biggest real lever remains PROF-2 (the
rdf4j/Xerces CGMES-import wall), not variant storage.

## Candidate 3 — Columnar variant *topology* (implemented + measured)

`p`/`q` are computed *characteristics*. The same array-of-structures-per-variant
pattern also holds the variant-dependent **topology**, which is more interesting for
the workloads that actually clone variants. This was built as a second slice on the
branch (`SwitchVariantStore`) and it is the bigger win.

- **`SwitchImpl.open` and `SwitchImpl.retained`** were per-variant `TBooleanArrayList`
  — one boolean per (switch, variant); every clone copied every switch's state. They
  now index a network-level flat `boolean[]` columnar store, extended once per clone
  by `NetworkImpl` (same shape as the terminal store), with merge/detach re-homing.
- **Measured on a 40 000-switch node-breaker network** (`bench.SwitchBench`,
  clone+remove ×50), same container:

  | | switch-clone-remove ×50 (median) |
  |---|---:|
  | Baseline (per-switch `TBooleanArrayList`) | **198 ms** |
  | Columnar `SwitchVariantStore` | **95–105 ms** |
  | | **≈ −48 to −52 %** |

  That is 2–5× the relative win of the p/q slice — because this network is
  switch-dominated and switch state is exactly what a topology clone copies.
- **Correctness: fully green** — iidm-impl 997/997, iidm-serde golden-file 303/303,
  byte-identical (switch open/retained is serialized).
- **Why it matters more:** security-analysis contingencies *change topology* per
  variant (open a switch / trip a line), and node-breaker networks (CGMES, UCTE) have
  very many switches — so switch state is on the real variant-cloning path, unlike
  p/q which the flow mostly overwrites anyway.
- **Rows are monotonic here** (no free list): `SwitchImpl` has no `removed` guard on
  its reads, so recycling a row would risk a use-after-free that the terminal store
  avoids via its `removed` flag. Adding a guard would enable recycling; left as a
  follow-up (memory-only, correctness is fine).
- **Further compaction:** `boolean[]` is one byte per flag; bit-packing each variant
  band into a `long[]` (one bit per switch) would make a clone an even smaller bitset
  copy. Left as a follow-up.
- **Not columnar-friendly:** the calculated bus-view/connectivity caches in
  `NodeBreakerTopologyModel`/`BusBreakerTopologyModel` use the object-based
  `VariantArray<VariantImpl>` (heterogeneous cached objects), which stay per-object —
  the primitive `open`/`retained` bits are the columnar target, not the cache.

## Full prototype — generic store, all high-cardinality primitive fields

The two bespoke stores were generalised and the sweep extended to the remaining
high-cardinality per-variant primitive state, so the prototype now covers every
per-object primitive variant field of the numerous object types:

- `VariantColumnStore` interface; `NetworkImpl` drives every registered store once
  per variant operation through a single list.
- `NumericVariantStore`: a **generic** flat columnar store of N `double` columns + M
  `int` columns, with per-column defaults and row recycling — one `arraycopy` block
  per variant per clone.
- Converted with it: **`NodeTerminal`** (v, angle, connected/synchronous component)
  and **`ConfiguredBusImpl`** (v, angle, fictitious P0/Q0, connected/synchronous
  component). Together with terminal p/q and switch open/retained, **all four
  high-cardinality primitive field groups are now columnar** (one row per terminal,
  per switch, per node terminal, per bus). Merge/detach re-home every store through a
  single `reHomeVariantStores(targetNetwork)` hook.
- **Correctness: iidm-impl 997/997, iidm-serde 303/303, byte-identical.**

**Clean same-container A/B** (no stores vs all four stores, measured back-to-back;
the earlier "5740 ms" PEGASE figure was a slow-machine-window artifact — this block
is the trustworthy comparison):

| bench | no stores | all 4 stores | Δ |
|---|---:|---:|---:|
| PEGASE (bus-breaker) clone/remove ×50 | 3049 ms | 1977 ms | **−35 %** |
| 40 000-switch node-breaker ×50 | 213 ms | 111 ms | **−48 %** |

**Where the win comes from, honestly.** On PEGASE the terminal-p/q store is the main
contributor; adding the node-terminal and configured-bus numeric stores on top was
within measurement noise. The reason is instructive: `ConfiguredBusImpl` still copies
a per-variant **list of connected terminals** (`ArrayList<List<BusTerminal>>`) on
every clone, and that object-list copy — not the numeric fields — dominates a bus's
clone cost. Lists of object references are not columnar-friendly, so columnarizing the
bus *numerics* helps little while that list copy remains.

**The new floor is the object-based `VariantArray<Variant>` path** — the per-voltage-
level topology/bus-view caches and the configured-bus terminal lists. These hold
heterogeneous per-variant *objects*, not primitives, so the columnar arraycopy
technique does not apply. After the primitive sweep, this is what remains of the clone
cost, and it is a genuine architectural boundary rather than more of the same work.

## Full replacement (toward a dedicated PR)

The prototype was carried through to a near-complete replacement of the per-object
trove variant storage. Infrastructure added to make it scale:

- **`NumericVariantStore`** now holds `double` + `int` + `boolean` columns with
  per-column defaults, per-instance-init `allocateRow`, and row recycling — a single
  generic store type for any object.
- **Lazy keyed registry** on `NetworkImpl`
  (`getOrCreateNumericVariantStore(key, …)`): a new columnar object type needs no
  `NetworkImpl` change, just a store key and a cached reference in the object.
- **Generic re-home cascade**: `reHomeVariantStores(NetworkImpl)` is a
  `MultiVariantObject` method cascading exactly like `extendVariantArraySize`
  (`AbstractIdentifiable` → extensions, `AbstractConnectable` → terminals,
  `AbstractDcConnectable` → DC terminals), so each converted class overrides only a
  local re-home of its own rows. Merge/detach iterate identifiables and cascade.

**Converted (≈24 classes / all high- and mid-cardinality primitive per-variant state):**
terminal p/q, switch open/retained, node-terminal v/angle/CC/SC, configured-bus
numerics; injection equipment (generator, load, battery, shunt, SVC, VSC, HVDC line,
boundary line + generation, area); bus-terminal `connected`; the whole DC subsystem
(DC switch, DC node, DC terminal, AC/DC + voltage-source converters); and 9
extensions (active-power control, standby automaton, load detail, voltage regulation,
remote reactive power, coordinated reactive control, HVDC angle-droop, control unit,
pilot point).

**Deliberately left on trove (a small, clean boundary):**
- **Tap changers and `RegulatingPoint`** — low-cardinality sub-objects that also carry
  *object-valued* per-variant state (the regulating terminal reference), which is not
  columnar-friendly. Being self-contained trove, they survive merge/detach with no
  re-home and coexist safely.
- Object-valued per-variant state everywhere (bus-terminal `connectableBusId` string
  list, configured-bus terminal lists, shunt section-count lists, regulating
  terminals) and the object-based `VariantArray<Variant>` topology/bus-view caches —
  not primitive, so out of scope for columnar storage.

**Correctness:** iidm-impl 997/997, iidm-serde golden-file 303/303,
cgmes-conversion 486/486 (assembled/merged microgrid round trips),
iidm-modification + security-analysis-api 532/532 — all byte-identical.

**A bug the wide test surface caught:** a cached-store-reference class must update
*both* its row **and** its store reference on re-home. `NodeTerminal` initially updated
only the row, so after a merge/detach it read a foreign store with a stale row —
an `ArrayIndexOutOfBounds` that only surfaced in the CGMES merged/assembled cases,
not the basic merge test. Fixed; the pattern is now uniform across all converted
classes.

## Recommendation

1. **Do not pursue copy-on-write** — it breaks the concurrency contract.
2. **Columnar SoA is the only safe architecture that attacks the cost, and two slices
   now prove it end-to-end** (both 997/997 + 303/303 green, byte-identical):
   - terminal p/q: **~−10–20 %** on bus-breaker PEGASE;
   - switch open/retained: **~−48–52 %** on a 40 k-switch node-breaker network.
   Each converted field buys a slice of the clone cost; the full win needs every
   primitive per-variant field converted — still a multi-week, high-risk rewrite, but
   the mechanism, lifecycle handling (merge/detach re-homing, row recycling) and
   thread-safety are now demonstrated.
3. **Topology (switch) is the higher-value target than p/q**, as suspected — it is on
   the real security-analysis path and gave the largest relative win (−48 %).
4. **The primitive columnar sweep saturates once terminals + switches are done.** The
   generic store makes converting further primitive fields cheap and safe, but the
   remaining clone cost is the object-based `VariantArray` path (topology/bus-view
   caches, bus terminal lists), which columnar storage cannot touch. Converting the
   lower-cardinality equipment numerics (generators, loads, tap changers, …) with the
   generic store is mechanical but has diminishing returns.
5. Practical stance: keep the shipped, low-risk wins (IIDM-F −45 %, IIDM-G −9–19 %).
   The full prototype demonstrates the mechanism, thread-safety and lifecycle handling
   end-to-end for a real ~−35 to −48 % clone speedup; treat productionising it as a
   deliberate project, greenlit if variant-heavy / node-breaker workloads become a
   priority. The biggest real lever overall still remains PROF-2 (the rdf4j/Xerces
   CGMES-import wall), which no variant work touches.

## JMH benchmark results (powsybl-benchmark, case6515rte, 6515-bus RTE grid)

Measured with the JMH suite (`gautierbureau/powsybl-benchmark`) built against this branch
(`powsybl-core 7.4.0-SNAPSHOT`). Clean A/B by swapping only the differing artifacts in the
local `.m2` between three points, everything else identical:

- **STOCK** = `5a3e7cc` (merge-base with main, none of our work);
- **PERF** = `6a356ab` (all earlier perf work, trove variant storage);
- **COLUMNAR** = branch HEAD (PERF + columnar variant storage).

### Variant clone + remove (new `VariantCloneBenchmark`, added to the suite)

| config | clone+remove µs/op | vs STOCK |
|---|---|---|
| STOCK | 23485.6 ± 832 | — |
| PERF (IIDM-F/G + lock-free VariantArray, trove) | 13419.7 ± 436 | −43 % |
| COLUMNAR (this branch) | 9236.7 ± 433 | **−61 % (2.5×)** |

Columnar's incremental contribution over PERF is **−31 %**, on non-overlapping CIs. The
absolute ~9 ms shows `VariantManager.cloneVariant` is now dominated by the O(N-objects)
traversal (it still visits every `MultiVariantObject`), not the array copy — a future lever.

### IIDM read / write / round-trip (`NetworkSerializationBenchmark`, STOCK vs branch), ms/op

| op | XIIDM | JIIDM | BIIDM |
|---|---|---|---|
| read | 164.7 → 165.0 (~0) | 99.8 → 95.4 (−4 %) | 77.1 → 72.6 (−6 %) |
| write (stream) | 174.6 → 147.7 (−15 %) | 172.6 → 153.9 (−11 %) | 150.9 → 124.5 (−17 %) |
| write (file) | 314.2 → 156.3 (−50 %) | 176.0 → 157.9 (−10 %) | 138.3 → 122.2 (−12 %) |
| round-trip copy | **7196.1 → 220.3 (−97 %, 32.7×)** | 216.8 → 197.6 (−9 %) | 232.2 → 201.3 (−13 %) |

The XIIDM copy 32× is real (not an artifact): `NetworkSerDe.copy` streams through an
unbuffered nio `Pipe`, so at STOCK every tiny StAX write was its own pipe syscall — a
stack profile showed 55 % of time in `UnixFileDispatcherImpl.read0`/`write0` and 0.2 % in
the actual XML encoding. PROF-1's 8 KB write buffering collapses millions of pipe writes
into a handful. The gradient (null-stream −15 %, jimfs-file −50 %, pipe-copy −97 %) tracks
exactly how badly each sink handles unbuffered tiny writes.

### CGMES export / import (`CgmesSerializationBenchmark`, STOCK vs branch), ms/op

| op | CGMES |
|---|---|
| export (write) | 1008.2 → 345.4 (**−66 %, 2.9×**) |
| import (read) | 2555.3 → 2325.2 (~−9 %, within noise) |

CGMES is RDF/XML, so PROF-1's StAX buffering lands hard on export (matches the −58 %
measured earlier). Import needs a CGMES-native case to show the query-cache wins.

### Next non-XML levers (profiler leads, not yet actioned)

Once the XML lock is gone, the top format-agnostic frames on the JSON/binary read+write
paths are: `RefChain.get` / `ref.get()` call volume (~3–7 %, top read frame — trivial body,
so it is call count from resolving the network ref per element/attribute during
construction), `NetworkIndex` HashMap resize (~2.6–3.5 %, pre-sizable on import), the
exporter's per-element iteration (`isElementWrittenInsideNetwork` 4.6 % + Guava concatenated
iterators / stream pipeline ~12 %), plus format-specific JSON `parseDouble` and binary Zstd
compression. These lift XML/JSON/BIN together and are the natural follow-up to the
XML-focused PROF-1 work.
