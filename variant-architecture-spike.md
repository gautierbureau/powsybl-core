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

### Real-workload relevance (the honest caveat)

`variant-clone-remove ×50` is a **synthetic** stress test. In real workloads:

- **CGMES import** — the #1 real cost — does no variant cloning at all.
- **Security analysis** clones variants but then writes them heavily (power-flow
  results into bus v/angle and terminal p/q), so the clone-*extend* is a small
  fraction of end-to-end SA time (dominated by the flow solve itself).

So even a perfect columnar rewrite (saving order ~1–2 s on the ×50 microbench)
moves real end-to-end times little. The biggest real lever remains PROF-2 (the
rdf4j/Xerces CGMES-import wall), not variant storage.

## Recommendation

1. **Do not pursue copy-on-write** — it breaks the concurrency contract.
2. **Columnar SoA is the only safe architecture that attacks the cost**, and its
   ceiling is real (22×+ on the extend). But it is a large, high-risk rewrite whose
   payoff lands on a workload pattern that is not the real bottleneck.
3. Practical stance: keep the shipped, low-risk wins (IIDM-F −45 %, IIDM-G −9–19 %);
   treat the columnar rewrite as a deliberate, separately-scoped project to greenlight
   only if variant-heavy workloads (not CGMES import) become a measured priority.
