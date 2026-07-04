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
| `cgmes-import`             | 7388 | 7198 | −190  |  −3% | hot path #1, mostly untouched |
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
- **`variant-clone-remove` and `cgmes-import` essentially flat** — this is the
  key profiling result, see §4.

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
off on models with large substation/voltage-level merge sets.

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

## 6. Remaining opportunities (not implemented), ranked by the benchmark

These are ordered by where the time actually is on this case.

### A. Cache parameterless structural SPARQL queries in `CgmesModelTripleStore` — *highest leverage*
`switches()`, `terminals()`, `acLineSegments()`, `transformers()`,
`transformerEnds()` each run a full-table SPARQL evaluation and are invoked
2–3× per import (the source even carries "consider caching…" hints). CGMES import
is the #1 hot path (7.4 s); memoizing these results (cleared on
`read`/`invalidateCaches`/`setQueryCatalog`) is the single most promising change.
**Risk:** must not serve stale data to the update/write flows — cache only the
read-during-import queries, with explicit invalidation.

### B. Reduce per-object variant-array cost — *addresses hot path #2*
Variant clone/remove is O(stateful objects) per operation because each
`MultiVariantObject` manages its own array. Options: batch the array
grow/shrink, or make `VariantArray` publish a single shared `volatile` array
instead of `Collections.synchronizedList` (see C). **Risk:** memory-visibility
correctness under the supported multi-thread variant mode; needs care and TCK
coverage.

### C. Drop the lock on the hottest variant read: `VariantArray`
`VariantArray` wraps its list in `Collections.synchronizedList`, so
`variants.get()` takes the list monitor on essentially every variant-scoped read
(`getBusView().getBus()` and friends — millions of calls). Backing it with a
plain array / `volatile`-published array, confining synchronization to structural
changes, removes that lock from the read path. **Risk:** same as B — this is the
highest-frequency path in the model, so correctness of the multi-thread mode must
be preserved (memory visibility, TCK).

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

## 7. Caveats / how to reproduce

- The before/after numbers are **separate JVM runs**; treat sub-20 % deltas on
  the fast stages as inside the noise. For publication-grade numbers, run BEFORE
  and AFTER interleaved (or via JMH forks) and repeat 3–5×.
- Reproduce: convert `case13659pegase.m` → `.mat` (`scipy.io.savemat`, struct
  name `mpc`), then run `bench.Bench <case.mat> <workdir> <label>` against the
  `main` jars and the branch jars respectively.
- The `.mat` conversion and the `Bench` harness live in the session scratchpad
  (`m2mat.py`, `bench/`).
