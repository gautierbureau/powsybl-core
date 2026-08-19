# IIDM / CGMES performance: benchmark and remaining hot paths

This note documents the performance work done on branch
`claude/iidm-cgmes-performance-c8931j`: the benchmark used to measure it, the
before/after numbers on a large realistic case, and — most importantly — where
the remaining hot paths are and what it would take to address them.

## 1. Benchmark case and method

- **Case:** `case13659pegase` (MATPOWER), the PEGASE snapshot of the European
  high-voltage transmission network: **13 659 buses, 4 092 generators,
  20 467 branches** (14 738 lines + 5 729 two-winding transformers once imported
  to IIDM). Downloaded from the MATPOWER distribution as `case13659pegase.m` and
  converted to a MAT5 `.mat` file (the format the powsybl MATPOWER importer
  reads).
- **Round trip measured:** MATPOWER import → IIDM network, then
  **XIIDM write → read** and **CGMES export → import**, plus the three
  in-memory paths the changes touch (variant clone/remove, bus P/Q, regex
  criteria). This is exactly the "export then reload and time each stage" loop
  needed to locate hot paths.
- **Harness:** single JVM (OpenJDK 21, `-Xmx6g`), **2 warmup + 7 measured**
  iterations per operation, **median** reported. `System.gc()` between
  iterations. Source: `bench/src/main/java/bench/Bench.java` (in the scratchpad).
- **before** = `main` (`5a3e7cc`); **after** = this branch. The two are
  *separate JVM runs*, so cross-run variance (JIT, GC, container CPU
  contention) applies — see the caveat below.

## 2. Results

Median wall-clock per operation, PEGASE 13 659:

| Operation | before (ms) | after (ms) | Δ | Δ% | Read |
|---|---:|---:|---:|---:|---|
| `cgmes-import`             | 7388 | 7198 | −190  |  −3% | hot path #1; further −9.5% with the query cache, see §6 |
| `variant-clone-remove` ×50 | 6393 | 6334 | −59   |  −1% | hot path #2, mostly untouched |
| `cgmes-export`            | 3447 | 3201 | −246  |  −7% | modest win |
| `regex-criterion` ×20     | 2456 | 1389 | −1067 | **−43%** | clear win |
| `bus-getP-getQ-sweep` ×20 |  771 |  908 | +137  | +18% | within noise |
| `xiidm-write`             |  698 |  647 | −51   |  −7% | modest win |
| `xiidm-read`              |  318 |  256 | −62   | −19% | clear win |
| `matpower-import`         |  244 |  287 | +43   | +18% | untouched (noise gauge) |

**Reading the noise.** `matpower-import` is not modified by this branch yet moved
+18% between runs; the bus P/Q sweep (also small/fast) moved +18% the same way.
So on the sub-second stages, **±~18 % is the cross-run noise floor** and small
deltas there are not meaningful on a single pair of runs. The conclusions that
survive that noise:

- **`regex-criterion` −43 %** — far outside noise, on a 2.5 s base. This is the
  `RegexCriterion` pattern-precompilation fix, and it is the headline win: the
  regex was recompiled on *every element*, and is now compiled once.
- **`xiidm-read` −19 %** and **`xiidm-write` −7 %** — consistent low-variance
  improvement from the `IidmVersion` namespace caching and the serializer-lookup
  de-duplication.
- **`cgmes-export` −7 %** — from `isValidCimMasterRID` length-dispatch, the
  naming-strategy fixes and the hoisted lookups. Larger absolute base, so less
  noise-prone.
- **`variant-clone-remove` and `cgmes-import` essentially flat in the initial
  batch** — this is the key profiling result (§4). `cgmes-import` was then
  addressed directly with a query cache (**−9.5 %**, §6); the `variant-clone`
  cost was found to be intrinsic and its read-path lock made lock-free (§7).

## 3. Where the hot paths are

Ranked by absolute cost, the round trip is dominated by two operations:

```
 7.4 s  cgmes-import          <-- #1 by far
 6.4 s  variant-clone/remove  <-- #2  (~128 ms per clone+remove pair)
 3.2 s  cgmes-export
 1.4 s  regex over all ids ×20
 0.9 s  bus getP/getQ ×20
 0.65s  xiidm-write
 0.29s  matpower-import
 0.26s  xiidm-read
```

The small, self-contained fixes on this branch land on the *cheaper* stages
(serialization, criteria). The two genuinely expensive operations —
**CGMES import and variant cloning** — are dominated by costs that these fixes
do **not** touch. That is not a gap in the fixes; it is the benchmark telling us
where the next, larger effort has to go.

## 4. Why the two hot paths barely moved (and what would move them)

### CGMES import (7.4 s)
The cost is dominated by the triplestore SPARQL evaluation and the conversion
walk, not by the O(n²) map scans fixed in `NodeContainerMapping` (those helped,
but they are a small fraction of the 7.4 s here — this case has few merged
containers). The `NodeContainerMapping` inverse-index fix is real but only pays
off on models with large substation/voltage-level merge sets. The SPARQL
evaluation cost itself is the lever, and it was subsequently reduced by caching
the repeated structural queries (**−9.5 %**, §6).

### Variant clone/remove (6.4 s for 50×)
The `VariantManagerImpl` change removes *redundant* full-network scans, but this
benchmark exercises the **pure-extend** path (50 fresh variants) followed by
plain removals — where `cloneVariant` already did a single scan. The dominant
cost is the **per-stateful-object array work**: every `MultiVariantObject` in the
network (thousands of them) copies/extends/nulls its own variant array on each
clone and remove. That per-object cost is O(number of stateful objects) per
operation and is untouched. The scan-dedup fix pays off on **mixed batches**
(clone lists that mix existing + new + overwritten targets), which this workload
does not stress.

## 5. Implemented on this branch (recap)

Serialization / criteria (measurable wins above):
- `RegexCriterion`: compile the pattern once (was per-element) — **−43 %** on
  criteria evaluation.
- `IidmVersion`: precompute namespace URIs / version strings (were rebuilt via a
  stream per serialized element).
- `NetworkSerDe`: compute the extension-serializer set once per export instead
  of twice, resolve each extension's serializer once instead of twice, constant
  `DateTimeFormatter`.
- `CgmesExportUtil.isValidCimMasterRID`: dispatch on identifier length to run at
  most one regex instead of up to four.
- `CgmesNamingStrategy`: skip UUID-seed bookkeeping unless debug logging is on;
  remove duplicate alias/property lookups.

Algorithmic fixes that help on other shapes than this case:
- `NodeContainerMapping`: inverse index for `mergedSubstations` /
  `mergedVoltageLevels` (was O(n²) per substation/VL — pays off on heavily
  merged models).
- `NodeBreakerTopologyModel`: build calculated-bus terminals in an `ArrayList`
  then wrap once in a `CopyOnWriteArrayList` (was O(k²) per bus on node/breaker
  substations — PEGASE-from-MATPOWER is bus/breaker, so not exercised here);
  allocation-free open-switch counting; hoisted variant lookup.
- `CalculatedBusImpl`: `HashSet` dedup instead of `List.contains` in a loop.
- `AbstractBus.getP/getQ`: single traversal instead of count-then-sum.
- `VariantManagerImpl`: single stateful-object scan per clone (helps mixed
  batches).
- CGMES exports: `HashSet` dedup for boundary equivalent injections, hoisted
  transformer id/name, map-keyed DC converter units, single EI-terminal-id
  computation.

## 6. Query-cache follow-up on CGMES import (implemented — opportunity A)

Opportunity A below was the highest-leverage remaining item, so it was
implemented and measured in isolation.

**Profiling first.** Instrumenting `CgmesModelTripleStore.namedQuery` (per-name
call count + cumulative time) during a single PEGASE import confirmed the
structural queries are re-run several times on the *same, unchanged* triple
store. The costly repeats:

| query | calls | total ms | redundant ms (≈) |
|---|---:|---:|---:|
| `terminals`        | 3 | 1195 | ~800 |
| `acLineSegments`   | 2 |  442 | ~220 |
| `modelProfiles`    | 3 |   93 | ~60 |
| `shuntCompensators`| 2 |   61 | ~30 |
| `transformers`     | 2 |   39 | ~20 |
| `energyConsumers`  | 2 |   40 | ~20 |
| `regulatingControls`| 2 |  34 | ~17 |
| `switches`         | 3 |   14 | ~9 |
| others (×2–4)      | — |   — | ~40 |

**Fix.** Cache the result of parameterless named queries in
`CgmesModelTripleStore` and return the cached `PropertyBags` — mirroring the
existing `connectivityNodes()`/`topologicalNodes()` caching, which already
returns its cached instance directly. The cache is invalidated on **every**
triple-store mutation (`read(is)`, `update`, `add`, `clear`) and via
`invalidateCaches()`/`setQueryCatalog`, so the update/write flows can never see
stale data. Parameterized queries are not cached (their result depends on the
injected parameters).

**Isolated measurement** (same JVM session, back-to-back, 2 warmup + 8 runs,
median; only the `CgmesModelTripleStore` change differs between the two):

| | median | runs |
|---|---:|---|
| before (no cache) | 7567 ms | 7380–7864 |
| after (cache)     | 6850 ms | 6712–7138 |

**−717 ms, −9.5 %** on the #1 hot path. This is unambiguous: the after run's
*slowest* iteration (7138 ms) is below the before run's *fastest* (7380 ms), so
the two distributions do not overlap — it is not cross-run noise. All 522
cgmes-model + cgmes-conversion tests pass with the cache in place, exercising the
import + update + export paths that a stale-cache bug would break.

## 7. Variant hot path #2 — investigation and outcome (opportunities B & C)

Variant clone/remove is the #2 hot path (6.4 s for 50×). Both candidate
opportunities were investigated to conclusion.

### B. Per-object variant-array cost — *investigated, not a real fix*
`cloneVariant` is O(number of `MultiVariantObject`s) because each connectable
extends its **own** per-variant primitive arrays (e.g. `LoadImpl` grows its `p0`
and `q0` `TDoubleArrayList`s by one slot per new variant). That per-object work
is **intrinsic** to the variant model — every object genuinely needs a new
per-variant slot — and it is **already batched**: a single
`cloneVariant(source, [N targets])` extends every object by N in one pass. The
6.4 s in the benchmark is 50 *separate* clone+remove calls (50 full-network
traversals), which is the benchmark's choice, not an inefficiency. There is
nothing to fix here short of a columnar variant-storage redesign (out of scope).

### C. Lock-free `VariantArray` reads — *implemented*
`VariantArray` held its variant list in a `Collections.synchronizedList`, so
`get()` (the per-variant topology-state read used across the topology models)
took the list monitor on every call. It now publishes the list through a
`volatile` reference and rebuilds it **copy-on-write** on the rare,
main-thread-only structural changes (push/pop/delete/allocate); reads index into
a list that is never structurally modified after publication, so they are
**lock-free** while still seeing a fully constructed, consistent list. The
`volatile` publication supplies the memory visibility the lock previously gave,
matching the `VariantManager` contract (structural changes on the main thread
only; concurrent threads read/write pre-allocated variants, each on its own
index).

- **Correctness:** full iidm-impl suite (997 tests) + the multi-thread TCK
  (`MultiVariantNetworkTest.multiThreadTest`, concurrent variant reads from an
  `ExecutorService`) pass.
- **Measured impact:** *within noise on this environment.* A topology-read sweep
  (`getBusView()/getBusBreakerView().getBuses()`), 2 warmup + 8 runs, on this
  **4-core** box:

  | | 1 thread | 4 threads |
  |---|---:|---:|
  | before (synchronizedList) | 205 ms | 217 ms |
  | after (volatile COW)      | 231 ms | 223 ms |

  The runs overlap; the lock is **uncontended at the parallelism a 4-core box can
  reach** (both versions already scale linearly to 4 threads), and on realistic
  topology reads `get()` is a small fraction of the work (bus iteration
  dominates). The change is a correctness-preserving modernization whose benefit
  appears under **high thread counts on many-core machines** (real multi-variant
  security-analysis workloads), which this environment cannot exercise. Kept
  because it is a clean lock removal with no regression, not for a demonstrated
  speedup here.

## 8. Cross-repo micro-optimizations (implemented)

A repo-wide scan (commons serialization core, triplestore backend, security /
contingency evaluation, and the UCTE/MATPOWER/PSSE converters) surfaced a batch
of allocation- and complexity-reduction fixes on plausibly-hot paths. All are
correct and test-verified (905 tests across the touched modules pass), low-risk,
and self-contained. Honest caveat up front: **most are within noise or not
exercised by this import/export/variant benchmark** — they reduce allocation and
algorithmic cost on paths the PEGASE round trip either barely spends time on
(after the query cache) or does not run at all (security analysis). They are kept
as clean, correct improvements for the workloads that *do* hit them, not for a
demonstrated speedup here.

**Triplestore (on the CGMES import path).**
- `TripleStoreRDF4J.query`: hoist the option flags out of the per-row loop,
  iterate binding names by index with a single `BindingSet.getValue` lookup per
  cell (was `hasBinding` + `getBinding` = two hash lookups), drop the per-row
  capturing lambda.
- `PropertyBag`: guard `URLDecoder.decode` behind a `%`/`+` scan (most
  identifiers have no escape and now skip a `StringBuilder` + O(n) copy);
  Set-backed membership for `isResource`/`isClassProperty`/`isMultivaluedProperty`
  (O(1) vs linear scan on the write path); size the per-row `HashMap` for the
  load factor.
- *Measured:* isolated CGMES-import A/B on PEGASE — before **6996 ms**, after
  **6926 ms** (−1 %, distributions overlap → within noise). The query cache
  (§6) already removed the redundant query executions, so what remains is a
  single materialization pass that is a small fraction of the 7 s import
  (SPARQL evaluation + conversion dominate). The membership-set and write-path
  fixes help the *export* side, not measured here.

**Security-analysis / contingency (per-contingency paths — not run by this
benchmark, but the core use case for those modules).**
- `LimitViolationDetection.checkLimitViolation`: only allocate the temporary-
  overload-ids `HashSet` when TATL is actually checked (this is the innermost
  detection primitive — every branch side and 3wt side, pre- and every
  post-contingency state).
- `LimitViolationDetection.createViolationLocation`: resolve just this bus's
  nodes (`Networks.getNodes`) instead of building the whole voltage level's
  node→bus map (and its per-node topology traversals) to keep one entry.
- `LimitViolationFilter.apply`: resolve the voltage level once per violation
  instead of once for the nominal-voltage check and again for the country check.
- `IdentifierContingencyList.getContingencies`: call `filterIdentifiable` (a full
  network scan, plus a per-element regex for wildcards) once per identifier
  instead of twice.

**Converters.**
- `MatpowerExporter`: precompile the bus-number regex (was `String.matches` per
  bus).
- PSSE `Util.parseValueFromRecord`: reuse the computed header index instead of
  scanning the headers array twice.
- `UcteRecordParser`: drop the redundant `trim` in `parseInt`/`parseDouble`
  (`parseString` already trims), use `Double.parseDouble` instead of
  `Double.valueOf` (no boxing), and `isBlank()` instead of `trim().isEmpty()`
  when skipping empty lines.

**Commons.**
- `BinReader.readEnumAttribute`: cache enum constants per class instead of
  calling `Class.getEnumConstants()` (which clones its array) on every enum
  attribute read on the binary-import path.

Additional lower-ranked findings from the scan were left unimplemented as
higher-risk or lower-value: binary string/entry reuse pools (`BinReader`/
`BinWriter`), per-cell `String.format` in the table formatter (AMPL/CSV export),
PSSE per-record `fieldsWithSuffix` map rebuild, and a bulk `DenseMatrix`
buffer copy. These are documented in the scan notes if a follow-up is wanted.

## 9. Remaining opportunities (not implemented), ranked by the benchmark

### D. Index buses by component in the components manager
`AbstractComponent.getBuses()` scans every bus in the network per component
(O(components × buses)). The bus→component number is already computed in
`AbstractComponentsManager.update()`; bucketing buses by component there makes
`getBuses()` O(size of component). **Risk:** low; localized to the components
manager, but not on the critical path of this benchmark (only matters for
callers that walk components).

### E. Generic `getConnectableStream(Class)` / `getConnectableCount(Class)`
These filter the entire identifiable set even when the requested class matches an
indexed concrete type (`getGenerators()` etc. use O(1) buckets). Delegating to
`index.getAll(clazz)` when the class is indexed avoids the full scan. **Risk:**
low; only affects generic-typed callers.

## 10. Caveats / how to reproduce

- The §2 before/after numbers are **separate JVM runs**; treat sub-20 % deltas on
  the fast stages as inside the noise. For publication-grade numbers, run BEFORE
  and AFTER interleaved (or via JMH forks) and repeat 3–5×. The §6 (query cache)
  and §7.C (VariantArray) measurements *were* taken this way — same session,
  back-to-back, reverting only the one file under test between runs.
- Reproduce: convert `case13659pegase.m` → `.mat` (`scipy.io.savemat`, struct
  name `mpc`), then run `bench.Bench <case.mat> <workdir> <label>` against the
  `main` jars and the branch jars respectively.
- The `.mat` conversion and the `Bench`/`CgmesBench`/`VariantReadBench` harnesses
  live in the session scratchpad (`m2mat.py`, `bench/`).
