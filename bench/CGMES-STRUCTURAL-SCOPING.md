# CGMES import memory — scoping the two structural options

Scope of the two remaining (post PR #18/#19/#20) levers on CGMES-import RAM:
**A. streaming query API** and **B. StAX profile reader replacing rdf4j for import**.
All numbers measured this session on PEGASE-13k (13 659 buses, ~104 MB CGMES) with the
`CgmesMemBench` / `CgmesQueryStats` harnesses in this directory, powsybl-core 7.4.0-SNAPSHOT,
JDK 21 HotSpot/G1, −Xmx8g. Query-stats run had the query cache in (PR #18 state), canonicalization
and lazy lists not (isolated measurement of what each option can win).

## Measured foundation — where the RAM actually is

| component | size | which option attacks it |
|---|---|---|
| allocation churn per import | ~3.2 GB | B (rdf4j parse+SPARQL is ~40–50 % of it) |
| peak heap during import | ~1.1–1.3 GB | both, differently |
| **resident rdf4j triple store** (load phase → end of convert) | **270 MB** | **B** (eliminated) |
| **materialized query results** (PropertyBags, sum of the big ones) | **~83 MB** | **A** (never materialized) |
| — of which `terminals` alone (59 324 rows × 12 cols) | 40 MB (~700 B/row) | A |
| — acLineSegments 14.7k / transformerEnds 11.5k / topologicalNodes 13.7k rows | 7–11 MB each | A |
| retained network after import | 130 MB | neither (conversion output) |
| triple-store load time | 6–12 s of the ~7–9 s import + load | B |

Note: PEGASE-from-matpower is bus-branch — `switches`, `operationalLimits`, `ratioTapChangers`
returned 0 rows here. On a real node-breaker grid those add materialized-result weight, so
option A's win grows somewhat on such cases.

## Option A — streaming query API (`forEachQueryResult`)

**Design.** rdf4j's `RepositoryConnection` must stay open while results are consumed, so an
`Iterator`-returning API would leak connection lifetime to callers. The safe shape is
callback-based: `void query(String query, Consumer<PropertyBag> consumer)` on `TripleStore`
(default method delegating to the materialized `query()`, so the API stays optional),
implemented in `TripleStoreRDF4J` by pushing rows inside the existing try-with-resources, plus
`CgmesModelTripleStore.forEachNamedQueryResult(name, consumer)`. Only one `TripleStore`
implementation exists in-repo (rdf4j), so API evolution is cheap.

**Adoption surface.** `CgmesModel` exposes 59 PropertyBags-returning methods; `Conversion`
consumes ~30 of them, almost all exactly once (measured: only `baseVoltages` 3×, `fullModels`/
`computedTerminals` 2× — and those go through caches). Only the top ~6 largest results are worth
converting (terminals, acLineSegments, transformerEnds, topologicalNodes, shuntCompensators,
energyConsumers).

**Conflict with the query cache (PR #18).** A streamed result cannot be cached. `terminals()` has
3 consumers (`computeTerminals`, `Update`, `Context.buildUpdateCache`) — but all can be fed from
the existing `cachedTerminals` map, so the right design streams *into* that map once and drops the
PropertyBags entirely. Same pattern applies per query: stream only where the orchestration
guarantees single consumption, otherwise keep the cached materialized path.

**Expected win (honest).** Bounded by the materialized-results total: ~83 MB before
canonicalization; PR #19 already dedupes the value strings, so the residual win is realistically
**~40–70 MB of peak (~5 %)** and little churn/time. On node-breaker cases somewhat more.

**Effort / risk.** ~2–4 days incl. tests; low-to-medium risk (connection lifetime, cache
interplay). **Verdict: not worth it as a general API on these numbers.** If wanted, do only the
surgical variant — stream `terminals` into `cachedTerminals` (−40 MB peak, one query, no API
change visible outside cgmes-model).

## Option B — StAX profile reader (replace rdf4j on the import path)

**Design.** A read-only `CgmesModel` implementation that parses EQ/SSH/TP/SV once each with StAX
into per-class column tables keyed by `rdf:ID` (CGMES RDF/XML is flat: one top-level element per
object, simple property children). The ~60 named queries become Java table scans + hash joins.
rdf4j stays for update/export/`store-as-extension`; selection via an import parameter, defaulting
to the new reader once proven. `CgmesModel` being an interface makes this pluggable without
touching `Conversion`.

**Complexity (measured from the catalog).** CIM16: 63 SELECTs + 37 update-phase SELECTs; CIM100
variants; **67 GRAPH clauses** (cross-profile joins EQ↔SSH↔TP↔SV that the tables must reproduce,
including `rdf:about` merge of the same object across files) and **146 OPTIONAL clauses**
(nullable columns). Plus boundary-set handling and 3 CIM versions (14/16/100). This is a
reimplementation of the query semantics, not a parser swap.

**Expected win.** Eliminates the 270 MB resident store (replaced by leaner tables, est. 60–100 MB)
and the rdf4j parse+SPARQL churn (~40–50 % of 3.2 GB): estimated **peak ~1.1 GB → ~0.6–0.7 GB,
churn roughly halved, load 6–12 s → ~2–4 s**. The only order-of-magnitude lever left.

**Effort / risk.** A 3–6 week project. Phase it: (1) EQ/SSH/TP/SV parse + the ~25 bus-branch
conversion queries — enough to run this PEGASE benchmark end-to-end; (2) node-breaker, limits,
regulating controls, boundary; (3) update-phase queries. Risk is high but well-gated: the 486
cgmes-conversion golden tests + conformity suites give a byte-identical acceptance bar, and this
harness gives the memory numbers. Keep rdf4j behind the parameter as permanent fallback.

**De-risking spike first (recommended before committing).** 1–2 days: StAX-parse EQ only into
tables and reproduce the two hardest join queries (`terminals`, `transformerEnds`), diffing row
sets against the SPARQL results on PEGASE-13k + one conformity node-breaker case. That answers
the only real design unknown (join/merge semantics) for ~5 % of the total cost.

## Recommendation

1. **Skip option A** as a general API (post-#19 win too small for the surface). At most do the
   surgical `terminals`→`cachedTerminals` streaming (−40 MB) if peak matters after #18–#20 land.
2. **Option B is the real lever** for both RAM and import time — but commit via the 1–2 day
   de-risking spike first, then decide with its result in hand. Everything needed to run it
   (harness, dataset, golden gate) is in place.
