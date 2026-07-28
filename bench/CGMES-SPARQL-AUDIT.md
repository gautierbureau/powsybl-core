# CGMES SPARQL catalog audit — well-defined & scaling

Question: *"can we verify that each SPARQL request is well-defined and scales well?"*

Harness: `bench/src/main/java/bench/CgmesQueryAudit.java` (this dir), three modes:
- `parse` — static: rdf4j SPARQL parser over every query in every catalog, with the runtime
  prefix block (rdf/cim/entsoe/eu) prepended and dummy params injected.
- `scale <zip...>` — dynamic: run every parameterless named query on N datasets of increasing
  size; report rows, best-of-3 time, and the log-log growth exponent d(log t)/d(log rows).
- `explain <zip> [query...]` — rdf4j `Explanation.Level.Timed` query plan (cartesian / cardinality).

Datasets (same bus-branch family, matpower→CGMES, ~10x triple span):
`case1888rte` 147K triples · `case6515rte` 530K · `pegase13k` 1.42M.
powsybl-core 7.4.0-SNAPSHOT (branch build), JDK 21 HotSpot/G1.

## 1. Well-defined (static parse) — PASS

All **160** real queries across CIM16 / CIM16-update / CIM100 / CIM100-update parse cleanly with
the rdf4j SPARQL parser (SELECT via `parseQuery`, INSERT/DELETE via `parseUpdate`), including the
6 parameterized ones (`numObjectsByType`, `allObjectsOfType`, `countrySourcingActors`,
`sourcingActor`, `update`, `create`) once realistic params are injected.

One anomaly, harmless: **`remove`** in CIM16.sparql (and the CIM100 include) is an *empty* query —
a `# query: remove` header at end-of-file with no body. It fails to parse (empty string) but is
**never referenced by any Java code** (grep: 0 hits). Dead stub. Calling it would return empty
`PropertyBags` (read) / log a warning (update) — no crash. Cosmetic only.

## 2. Scales well (dynamic) — PASS

Timed each dataset in an **isolated JVM** (co-resident stores in one JVM inflate later datasets via
GC pressure — that confound produced a spurious exp≈1.67 in the first pooled run; corrected below).

| query | rows 1888→13659 | time ratio | exp (t vs rows) |
|---|---|---|---|
| terminals | 9.3× | 3.5× | 0.56 |
| acLineSegments | 7.5× | 3.2× | 0.58 |
| topologicalNodes | 7.2× | 2.9× | 0.54 |
| svVoltages | 7.2× | 3.8× | 0.67 |
| connectivityNodeContainers | 6.0× | 2.2× | 0.44 |
| transformerEnds / regulatingControls / synchronousMachines* | — | — | 0.5–0.8 |

Every on-path query is **linear-to-sublinear in its result size** (exp ≤ ~0.8; the sub-1 slope is
fixed per-query overhead amortizing as output grows). No superlinear behavior.

`EXPLAIN` (Timed) on the two former "suspects" confirms **clean index-based join plans**:
`costEstimate`/`resultSizeEstimate` match `resultSizeActual` at every node, joins are left-deep on
bound keys, no cartesian product. e.g. topologicalNodes on pegase: 155 ms for 13.6K rows, deepest
`StatementPattern FROM NAMED CONTEXT` costEstimate 2.7K / actual 13.6K.

## 3. The one genuine scaling wart — `graph()` (off the import path)

`# query: graph` = `SELECT DISTINCT ?graph WHERE { GRAPH ?graph { ?s ?p ?o } }`.
Returns the set of named-graph URIs — **4 rows** (EQ/SSH/TP/SV) on every case — but must **scan
every statement** to do it (rdf4j MemoryStore has no distinct-context shortcut in SPARQL):

| dataset | triples | graph() time |
|---|---|---|
| case1888rte | 147K | 40 ms |
| case6515rte | 530K | 128 ms |
| pegase13k | 1.42M | 357 ms |

Time tracks **triple count** (≈9.7× triples → ≈8.8× time, exp≈0.96 vs store size), i.e. O(store)
to return O(contexts). Classic "scales with the wrong quantity."

**But: `graph()` has zero callers in the entire repo** — dead `CgmesModel` public API. Not on the
import path, not in export, not in tests. So there is **no live impact** and nothing to fix for the
import-memory/time goal. It is only a latent trap for a hypothetical external caller.

If ever wanted, the O(contexts) replacement is one line — `RepositoryConnection.getContextIDs()`
reads the store's context index directly instead of a full triple scan. Not worth a PR against a
method nobody calls (changing dead public API is more risk than value).

## Verdict

The CGMES SPARQL catalogs are **well-formed and scale correctly**: all 160 queries parse, and every
query the import actually executes is linear-or-better in its output with healthy rdf4j join plans.
There is no query-level scaling defect on the hot path. This is consistent with the earlier memory
finding — the import's cost floor is the rdf4j store itself (parse + resident statements), not any
individual query — so the remaining levers stay the structural ones (store replacement / disk-backed
store), not query rewrites. The only blemishes are two dead catalog entries (`remove` stub, `graph`
query), neither reachable.
