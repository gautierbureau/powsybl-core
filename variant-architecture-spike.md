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

## Candidate 3 — Columnar variant *topology*, not just characteristics

`p`/`q` are computed *characteristics*. The same array-of-structures-per-variant
pattern also holds the variant-dependent **topology**, which is more interesting for
the workloads that actually clone variants:

- **`SwitchImpl.open` and `SwitchImpl.retained`** are per-variant `TBooleanArrayList`
  — one boolean per (switch, variant). Every clone copies every switch's open/retained
  state. This is the *same* AoS pattern as terminal p/q, so the *same* columnar
  treatment applies — and booleans bit-pack: a whole variant's switch states become a
  `long[]` bitset, so a clone is a single `System.arraycopy` of one bitset and reads
  are a bit test. Extremely compact and cache-friendly.
- **Why it matters more:** security-analysis contingencies *change topology* per
  variant (open a switch / trip a line), and node-breaker networks (CGMES, UCTE) have
  very many switches. So switch-state variant handling is on the path of the real
  variant-cloning workload, unlike computed p/q which is mostly overwritten by the
  flow anyway. A `SwitchVariantStore` (bitset-per-variant) is the natural next slice
  and likely a better bang-for-buck than p/q.
- **Not columnar-friendly:** the calculated bus-view/connectivity caches in
  `NodeBreakerTopologyModel`/`BusBreakerTopologyModel` use the object-based
  `VariantArray<VariantImpl>` (heterogeneous cached objects), which stay per-object —
  the primitive `open`/`retained` bits are the columnar target, not the cache.

## Recommendation

1. **Do not pursue copy-on-write** — it breaks the concurrency contract.
2. **Columnar SoA is the only safe architecture that attacks the cost.** The terminal
   p/q slice proves it works end-to-end (997/997 + 303/303 green, byte-identical) for
   ~−10–20 %; the ceiling per field is large (22× on extend). The full win needs every
   primitive per-variant field converted — a multi-week, high-risk rewrite.
3. **If pursuing it, start with switch `open`/`retained` (Candidate 3), not p/q** — a
   bitset-per-variant `SwitchVariantStore` is compact, and switch state is on the real
   topology-changing (security-analysis) path, whereas p/q is largely overwritten by
   the flow.
4. Practical stance: keep the shipped, low-risk wins (IIDM-F −45 %, IIDM-G −9–19 %);
   treat the full columnar rewrite as a deliberate, separately-scoped project, greenlit
   only if variant-heavy / node-breaker workloads become a measured priority. The
   biggest real lever overall remains PROF-2 (the rdf4j/Xerces CGMES-import wall).
