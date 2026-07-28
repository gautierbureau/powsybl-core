# PEGASE-13k CGMES import — RAM benchmark

Harness: `bench/src/main/java/bench/CgmesMemBench.java` (this dir).
Target: powsybl-core **7.4.0-SNAPSHOT** (current session build in `~/.m2`), stock JDK 21 HotSpot, G1GC.
Data: `../pegase13k.zip` (13 659-bus PEGASE as CGMES EQ/SSH/SV/TP, ~104 MB uncompressed / 5.5 MB zip),
generated from `case13659pegase.mat` earlier this session.

## Run

    cd bench
    javac -cp "$(cat cp.txt)" -d target/classes src/main/java/bench/CgmesMemBench.java
    java -Xmx8g -XX:+UseG1GC -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
      -cp "target/classes:$(cat cp.txt)" bench.CgmesMemBench ../pegase13k.zip 2 true

Allocation attribution:

    java ... -XX:StartFlightRecording=filename=pegase.jfr,settings=profile,disk=true ... ../pegase13k.zip 1 false
    jfr view allocation-by-class pegase.jfr
    jfr print --events jdk.ObjectAllocationSample --stack-depth 12 pegase.jfr | grep -oE 'com\.powsybl[^ (]*' | sort | uniq -c | sort -rn

## Measured (Network.read of the CGMES zip, default params)

| metric | value |
|---|---|
| import time | ~7.3 s (12.3 s under JFR) |
| **allocation churn** | **~3.2 GB** per import (~30x the 104 MB uncompressed input) |
| **peak heap during import** | **~1.32 GB** |
| retained (network live) | **+130 MB** |
| retained after drop | ~0 MB (no leak — model closed mid-convert) |
| network | 8354 substations / 8354 VLs / 13659 buses |

Consistent with the prior-session scoping (churn ~3.6 GB, peak ~1 GB, retained ~149 MB on 7.2.1);
differences are version/heap/params.

## Where the churn goes (JFR allocation-by-class)

rdf4j-dominated: `byte[]` 22% + `String` 13% (RDF/XML parse + string handling), then the SPARQL /
MemoryStore machinery — `ParsedIRI` 5.4%, `ArrayBindingSet` 5.1%, `Value[]` 4.2%, `MemStatementIterator`
3.6%, `MemStatementList`/`MemStatement[]`/`MemStatement` ~6.5%, `SAXFilter$ElementInfo` 1.8%, etc.

Powsybl-owned call sites driving it (allocation-sample counts): `TripleStoreRDF4J.query` /
`CgmesModelTripleStore.query`/`namedQuery` (SPARQL execution — top), `PropertyBag.extractIdentifier`/`getId`,
`NodeContainerMapping.substationsIds`/`addAdjacencyThroughTransformerEnds`, `AbstractCgmesModel.computeTerminals`.

## Retained network (130 MB) — class histogram highlights

Bulk is HashMap/String/byte[]/Object[]. Notable powsybl-side retention: **~104k `Properties` +
~104k `PropertiesContainer` + ~104k `ConcurrentHashMap`** (roughly one per identifiable) — the CGMES
properties stored per object.

## Ranked, low-risk optimization opportunities (confirmed against this profile)

1. **Dedupe repeated SPARQL queries** (`baseVoltages` 3x; `terminals`/`switches`/`transformers`/
   `transformerEnds`/`acLineSegments`/`seriesCompensators` 2x). Query execution is the top powsybl
   allocator — thread results through the call orchestration rather than re-querying.
2. **Canonicalize `PropertyBag` value strings** at materialization in `TripleStoreRDF4J.query` (~L208):
   one canonical `HashMap<String,String>` per query/import collapses repeated ids/URIs.
3. **Lazy-init the 3 always-empty `ArrayList`s in `PropertyBag`** (resourceNames / classPropertyNames /
   multiValuedPropertyNames) — dead weight per result row.
4. (bigger) streaming query API for the convert-once-and-discard sites; (structural) StAX profile reader
   instead of parse-all-into-rdf4j + SPARQL; (upstream) rdf4j per-iteration `System.getProperty`.

## Peak decomposition — minimum-viable-heap bisection (with PRs #18+#19+#20 applied)

Ground truth for "what peak really is": the smallest -Xmx the import completes in.

| -Xmx | result | import time |
|---|---|---|
| 8 g | ok (peak reading 1.07–1.15 GB) | ~7–8 s |
| 1024 m | ok | ~18 s* |
| 768 m | ok | ~12 s |
| 640 m | ok | ~12 s |
| 512 m | **ok** | ~14 s |
| 448 m | **OutOfMemoryError** | — |

(*container noise; times under tight heap include real GC-pressure cost)

**True peak demand ≈ 480–510 MB.** The 1.1–1.3 GB "peak heap" readings at large -Xmx are
~60 % GC slack — G1 defers collection until pressured; it is headroom, not live data.

**Composition of the ~500 MB live floor** (all coexist at end-of-convert, because
`cgmes.close()` runs at the very end of `Conversion.convert()`, after the SSH/SV update phase):
- ~270 MB rdf4j MemoryStore (live for the whole conversion)
- ~130 MB the IIDM network being built (the product — irreducible)
- ~60–80 MB cached query results (#18) + update-phase Context caches
- ~30–50 MB working set / fragmentation headroom

**Remaining non-rewrite levers on the floor** (before replacing rdf4j):
1. Progressive context release — clear the EQ (and TP) graphs from the store before the update
   phase, IF the update catalog (37 SELECTs) only queries SSH/SV. EQ is half the input; potential
   ~100+ MB off the floor. Needs an audit of the update queries' GRAPH clauses. Medium effort/risk.
2. Drop the parameterless-query cache and Context update caches before the end-of-convert overlap
   (~60–80 MB, easy, small time cost from re-running any still-needed query).

With both, the floor could reach ~350–400 MB. Below that the remainder IS the store
(197 B/statement — rdf4j MemoryStore has no sizing knob), i.e. only Option B (StAX reader /
store replacement) goes further: estimated floor ~250–300 MB (lean tables 60–100 MB + network).

Also verified: the #18 query cache is safe across the mid-import `setQueryCatalog` switch —
it calls invalidateCaches(). (Correction: the base and update catalogs DO share 20 query names,
so the invalidation is what carries the safety, not name disjointness.)

## Progressive-EQ-release experiment — hypothesis REFUTED (do not ship)

Prototyped `cgmes.clear(CgmesSubset.EQUIPMENT)` right before the SSH/SV update phase (guarded by
the same no-extension condition as the final close). Audit + gate: the 24 update-catalog queries
never need the EQ graph (SSH/SV re-assert the type triples of the objects they update; TP kept for
Terminal.TopologicalNode) — cgmes-conversion 486/486 byte-identical WITH the release in place.

But it moves nothing: full-import min viable heap stayed 448 MB OOM / 512 MB OK, and load-only
completes in 336 MB. Therefore the binding peak is at the END OF BASE CONVERSION — full store
(270 MB) + built network (130 MB) + results/caches coexist there, BEFORE any point where EQ can
legally be released (base conversion is what queries EQ). The same argument kills dropping the
caches early. Prototype reverted.

**Sharpened conclusion:** with rdf4j in place, the ~480-510 MB peak floor is irreducible from the
conversion side. The only remaining levers are on the store itself:
- Option B (StAX reader / purpose-built store) — est. floor ~250-300 MB, plus churn/time wins;
- or a "low-memory import mode" swapping rdf4j MemoryStore for a disk-backed rdf4j store
  (NativeStore/LmdbStore): peak would drop toward network-size (~150-200 MB) at a significant
  import-time cost - a config option, not a rewrite. Not prototyped.
