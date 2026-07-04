# powsybl-core performance tracker

Tracks the performance findings gathered by code scans across the repo, what has
been implemented on branch `claude/iidm-cgmes-performance-c8931j`, and what
remains. Companion to `iidm-cgmes-performance.md` (which holds the benchmark
method, the hot-path ranking, and the measured results in narrative form).

**Status legend:** ✅ done · ⬜ pending · 🔬 investigated, not a real fix ·
🌿 covered by another branch · 📏 measured.

**How to read impact:** relative to the affected module's hot path. Per the
benchmark caveat, most micro-optimizations are *within noise on the PEGASE
import/export/variant benchmark specifically*; the two headline wins below are
the only ones with a clean, isolated measurement.

Commits: `c33ef79` initial iidm/cgmes batch · `1f06d99` CGMES query cache ·
`cc0ee3d` VariantArray lock-free · `ab5b375` cross-repo batch.

---

## 1. Measured results (📏)

Isolated before/after, PEGASE `case13659pegase` (13 659 buses), median of
2 warmup + 7–8 runs, same JVM session reverting only the file under test.

| Change | Operation | Before | After | Δ | Verdict |
|---|---|---:|---:|---:|---|
| **XML export: bypass StAX per-write lock** (PROF-1) | cgmes-export | 3231 ms | 1359 ms | **−58 %** | clear win — found by profiling |
| **XML export: bypass StAX per-write lock** (PROF-1) | xiidm-write | 639 ms | 375 ms | **−42 %** | clear win |
| **Variant methods: loop not stream** (IIDM-F) | variant-clone-remove ×50 | 5900 ms | 3223 ms | **−45 %** | clear win, non-overlapping — found by profiling |
| `RegexCriterion` precompile | criteria eval ×20 | 2456 ms | 1389 ms | **−43 %** | clear win, outside noise |
| CGMES query cache | cgmes-import | 7567 ms | 6850 ms | **−9.5 %** | clear win, non-overlapping |
| Triplestore materialization | cgmes-import | 6996 ms | 6926 ms | −1 % | within noise (cache already removed redundant runs) |
| `VariantArray` lock-free | topology read (1/4-thread) | 205/217 ms | 231/223 ms | ~0 | within noise on 4 cores; benefit is many-core |
| Initial iidm/serde batch | xiidm-read / xiidm-write | 318/698 ms | 256/647 ms | −19 % / −7 % | consistent, low-variance |

Full context and the noise-floor discussion are in `iidm-cgmes-performance.md`
§2, §6, §7, §8.

---

## 1b. Profiling findings (JFR CPU + allocation, PEGASE 13659)

Static scans kept landing within noise, so the round trip was profiled with JFR
(`settings=profile`). Leaf-frame attribution of CPU samples:

**CGMES import (7 s) is ~90 % third-party, ~5 % powsybl.** By leaf frame:
rdf4j 49.7 %, Xerces XML parser 10 %, JDK String/HashMap/WeakHashMap 35 %
(overwhelmingly called *from inside* rdf4j IRI parsing / symbol tables /
statement storage), **powsybl 5 %**. Top methods: `MemStatementIterator`,
`ParsedIRI.parsePctEncoded`, `MemIRI`, `SAXFilter.startElement`,
`MemValueFactory`. Implication: powsybl-side import micro-opts have a ~5 %
ceiling; the query cache (−9.5 %) worked precisely because it removed whole
rdf4j query *executions*. Real further gains require attacking rdf4j (fewer
queries, a faster RDF parser, a lighter store, or streaming the parse) — big
architectural changes, tracked as PROF-2.

**XML export is dominated by per-write lock acquisition in the JDK StAX writer.**
`ReentrantLock.initialTryLock` (via `jdk.internal.misc.InternalLock`) was the #1
hot method of the whole round trip (10.4 %), all from
`com.sun.xml.internal.stream.writers.UTF8OutputStreamWriter.write` /
`XMLStreamWriterImpl` under the `IndentingXMLStreamWriter` — i.e. CGMES export +
xiidm-write. Every StAX write call locks. Actionable — tracked as PROF-1.

**Variant clone (6.3 s):** the per-object trove-array extend/delete is intrinsic
(IIDM-B), but profiling also found a per-object *stream pipeline* (`wrapSink`
~7 %) that was **not** intrinsic — fixed in IIDM-F (−45 %).

---

## 2. Implemented (✅)

### iidm-impl / iidm-api — `c33ef79`, `cc0ee3d`
| ID | Finding | File |
|---|---|---|
| IIDM-1 | Build calculated-bus terminals in an `ArrayList`, wrap once in `CopyOnWriteArrayList` (was O(k²) per bus) | `NodeBreakerTopologyModel` |
| IIDM-3 | Materialize stateful-object list once per clone (was up to 3 full scans) | `VariantManagerImpl` |
| IIDM-4 | Allocation-free open-switch counting in the connect path | `NodeBreakerTopologyModel` |
| IIDM-7 | Hoist `variants.get()` out of the vertex loop | `NodeBreakerTopologyModel.getBusesFromBusViewBusId` |
| IIDM-8 | `HashSet` dedup instead of `List.contains` in a loop | `CalculatedBusImpl.buildConnectableTerminalsCache` |
| IIDM-9 | Single traversal in `getP`/`getQ` instead of count-then-sum | `AbstractBus` |
| IIDM-C | Lock-free `volatile` copy-on-write reads | `VariantArray` (`cc0ee3d`) |
| **IIDM-F** | **Loop instead of a stream per object in the 4 variant-array methods (−45% on clone/remove)** | `AbstractIdentifiable` (`be70681`) |

### cgmes-conversion — `c33ef79`, `1f06d99`
| ID | Finding | File |
|---|---|---|
| CGMES-A | Cache parameterless structural SPARQL queries (**−9.5 %**) | `CgmesModelTripleStore` (`1f06d99`) |
| CGMES-1 | Inverse index for `mergedSubstations`/`mergedVoltageLevels` (was O(n²)) | `NodeContainerMapping` |
| CGMES-3 | Length-dispatch `isValidCimMasterRID` (≤1 regex vs 4) | `CgmesExportUtil` |
| CGMES-4 | Skip UUID-seed bookkeeping unless debug logging on | `CgmesNamingStrategy` |
| CGMES-5 | `HashSet` boundary equivalent-injection dedup | `TopologyExport`, `SteadyStateHypothesisExport` |
| CGMES-6 | Map-keyed DC converter units (was O(k²)) | `EquipmentExport.getAcDcConvertersUnit` |
| CGMES-7 | Single equivalent-injection-terminal-id computation | `StateVariablesExport` |
| CGMES-8 | Remove duplicate alias/property lookups | `CgmesNamingStrategy` |
| CGMES-12 | Hoist repeated 3WT id/name and switch-bus getters | `EquipmentExport`, `TopologyExport` |

### iidm-serde / iidm-criteria — `c33ef79`
| ID | Finding | File |
|---|---|---|
| SERDE-1 | Precompute namespace URIs / version strings | `IidmVersion` |
| SERDE-2 | Compile the pattern once (**−43 %**) | `RegexCriterion` |
| SERDE-3 | Compute extension-serializer set once per export; resolve each once | `NetworkSerDe` |
| SERDE-7 | Constant `DateTimeFormatter` | `NetworkSerDe` |

### triple-store — `ab5b375`
| ID | Finding | File |
|---|---|---|
| TS-1 | Single `getValue` lookup per cell + no per-row lambda | `TripleStoreRDF4J.query` |
| TS-2 | Guard `URLDecoder.decode` behind a `%`/`+` scan | `PropertyBag` |
| TS-3 | Set-backed resource/class/multivalued membership (O(1)) | `PropertyBag` |
| TS-4 | Size result-row `HashMap` for the load factor | `PropertyBag` |
| TS-6 | Hoist invariant `getNamespace("data")` out of the values loop | `TripleStoreRDF4J.addMultivaluedProperty` |

### security-analysis / contingency — `ab5b375`
| ID | Finding | File |
|---|---|---|
| SEC-1 | Resolve one bus's nodes instead of the whole-VL node→bus map | `LimitViolationDetection.createViolationLocation` |
| SEC-2 | Allocate temp-overload `HashSet` only when TATL is checked (branch + 3wt) | `LimitViolationDetection.checkLimitViolation` |
| SEC-3 | Resolve the voltage level once per violation | `LimitViolationFilter.apply` |
| SEC-4 | `filterIdentifiable` once per identifier instead of twice | `IdentifierContingencyList.getContingencies` |

### iidm-criteria — `114a8a4`
| ID | Finding | File |
|---|---|---|
| SERDE-5 | EnumSet membership instead of `List.contains` (list getter kept) | `SingleCountryCriterion`, `TwoCountriesCriterion`, `AtLeastOneCountryCriterion` |
| SERDE-4 | Direct loop instead of a per-call stream pipeline | `AtLeastOneCountryCriterion`, `AtLeastOneNominalVoltageCriterion` |
| SERDE-6 | `ImmutableSet` membership instead of `List.contains` (list getter kept) | `PropertyCriterion` |

### converters / commons — `ab5b375`, `9e00cd2`
| ID | Finding | File |
|---|---|---|
| CONV-8 | Precompile bus-number regex | `MatpowerExporter` |
| CONV-2 | Reuse computed header index instead of scanning twice | `psse-model Util.parseValueFromRecord` |
| CONV-5 | Drop redundant trim, avoid boxing, use `isBlank` | `UcteRecordParser` |
| CONV-1 | Cache the suffixed field map per (fields, suffix) instead of rebuilding per record | `psse-model Util.fromRecord`/`toRecord` (`9e00cd2`) |
| CONV-3 | Precompile the comment/space-normalization regexes (was `String.replaceAll` per line) | `psse-model LegacyTextReader` (`9e00cd2`) |
| CONV-4 | Pad with a `StringBuilder` instead of two `String.format` per field | `ucte-network UcteRecordWriter.alignAndTruncate` (`9e00cd2`) |
| CMN-1 | Cache enum constants per class (avoid `getEnumConstants()` clone) | `BinReader.readEnumAttribute` |
| CMN-3 | Decode strings into a reusable scratch buffer (no throwaway `byte[]` per string) | `BinReader`/`BufferedChannelReader` (`f6af75d`) |
| CMN-2 | Name-keyed per-type index array + index-ordered dictionary (no `TypedName` per attribute; byte-identical output) | `BinWriter.writeEntry` (`f6af75d`) |

---

## 3. Investigated — not a real fix (🔬)

| ID | Finding | Conclusion |
|---|---|---|
| IIDM-B | Per-object variant-array cost in clone/remove | The `TDoubleArrayList`/`TByteArrayList` extend/delete per object is intrinsic (already batched by `cloneVariant(source,[N])`; only a columnar redesign would change it). **But** profiling found a *separate*, non-intrinsic per-object stream pipeline in the same methods — fixed in IIDM-F (−45 %). Lesson: "intrinsic" was only half true; the profiler found the other half. |
| CMN-5 / CONV-6 | `String.format("%g")` per numeric cell (table/AMPL export) | No `DecimalFormat` pattern reproduces `%g` (6 sig-figs with conditional fixed/scientific switching), so any change alters AMPL/CSV output and breaks golden-file tests. Not safe to change without a format-behavior decision. |
| CMN-4 | `writeString` allocates a `byte[]` per string (`getBytes`) | Avoiding it needs a `CharsetEncoder` into a reusable buffer with length back-patching into `SegmentedByteBuffer` — real complexity/risk for one array per string. Deferred as not worth it. |

---

## 4. Pending (⬜) — ranked within each area by value

### Profiler-found (JFR)
| ID | Finding | File / location | Impact | Effort · Risk |
|---|---|---|---|---|
| ✅ PROF-1 | XML export took ~10 % of round-trip CPU on a per-write `InternalLock` in the JDK StAX writer | `commons/xml XmlUtil` + `UnsynchronizedBufferedWriter` + `FlushOnEndDocumentStreamWriter` (`5fdc6da`) | **Done — cgmes-export −58 %, xiidm-write −42 %** | — |
| PROF-2 | CGMES import is ~90 % rdf4j + Xerces; powsybl is ~5 % (hard ceiling) | `triple-store-impl-rdf4j` + rdf4j MemoryStore/RDFXML parser | High (the #1 hot path) but architectural | High · **High** — fewer/cheaper SPARQL queries (cache done), a faster RDF/XML parser, a lighter triple store, or streaming the parse instead of loading a full in-memory store |

**PROF-1 (done, `5fdc6da`).** Build the StAX writer over a non-synchronized
buffered writer (encoding delegated to `OutputStreamWriter`, so bytes are
unchanged) instead of directly over the OutputStream. A first attempt truncated
CGMES export because several export paths finalize the writer with
`writeEndDocument()` only (no flush/close) — so the returned writer is wrapped to
flush on `writeEndDocument` (`FlushOnEndDocumentStreamWriter`, via the staxutils
`StreamWriterDelegate` already on the classpath), and the buffered writer never
closes the underlying stream (JSR-173). The whole change is confined to
`commons/xml`; no caller is modified. Byte-identical: commons 307, iidm-serde 303,
cgmes-conversion 486, cgmes-completion 1 all pass, incl. the CGMES export→import
round trip. **Applies to all powsybl XML export.**

### Converters — CONV-1/3/4 done (`9e00cd2`); remaining below
| ID | Finding | File / location | Impact | Effort · Risk |
|---|---|---|---|---|
| CONV-6 | `String.format("%g")` per numeric cell (AMPL export, no column `NumberFormat`) | driver: `ampl-converter .../BasicAmplExporter.java` columns; cost: `commons AbstractTableFormatter.format` 93–100 | Med (dominant AMPL export cost) | Med · Med — cache `DecimalFormat` per column; `%g` semantics must be matched |
| 🌿 MATH-7 | Element-wise bounds-checked copy / scans in `DenseMatrix` | `math DenseMatrix.java` `copyValuesFrom`, `resetRow`/`resetColumn`/`removeSmallValues` | — | **Covered by branch `optim_dense_matrix`** (`a7222d2` "Use double[] in DenseMatrix", reworks the backing store from `ByteBuffer` to `double[]`). Do not duplicate here. |

### Commons serialization core — CMN-1/2/3 done; CMN-4 and CMN-5 rejected (see §3); remaining below
| ID | Finding | File / location | Impact | Effort · Risk |
|---|---|---|---|---|
| CMN-6 | `CSVParser`+`StringReader` per array attribute (import) | `xml/XmlReader.java` `readAndSplitStringArray` 145–168 | Med | Med · Low — hand-rolled split; primitive int parse |
| CMN-7 | boxing + stream + join per array attribute (export) | `xml/XmlWriter.java` 181–195 | Low-med | Low · Low — reused `StringBuilder` loop |
| CMN-8 | per-node `Context` allocation (JSON read & write) | `json/JsonWriter.java` 96/51, `JsonReader.java` 273–285 | Low-med | Med · Low — pool by stack depth |
| CMN-9 | boxed `Integer`/`Boolean` on required int/bool attribute read | `xml/XmlUtil.java` 141–144, 161–164 | Low-med | Low · Low — primitive-returning helpers |
| CMN-10 | `getServices()` `synchronized` on every call | `util/ServiceLoaderCache.java` 30–35 | Low-med (call-site dependent) | Low · Low — `volatile` + DCL or eager init |
| CMN-11 | boxed `IntStream` + linear `contains` per provider (per file header) | `xml/XmlReader.java` `readExtensionVersions` 60–70 | Low (per file) | Low · Low — `HashSet` + plain for-loop |
| CMN-12 | numeric `toString` per attribute (XML export) | `xml/XmlWriter.java` 144–179 | Potentially large but inherent | High · High — needs a custom char-buffer writer; not worth it under StAX |

### Triplestore — remaining
| ID | Finding | File / location | Impact | Effort · Risk |
|---|---|---|---|---|
| TS-5 | `getLocals` recompiles a regex via `String.split` per multivalued row | `triple-store-api PropertyBag.getLocals` 54–64 | Med (CGMES-layer frequency) | Low · Low — cache `Pattern` per separator |
| TS-7 | `distinctResults` materializes a full dedup `Set<BindingSet>` per query | `TripleStoreRDF4J.query` 200 | Potentially large heap/CPU on huge results | Med · **Med-high** — correctness safeguard; only bypass for `DISTINCT`/explicit `GRAPH` |
| TS-8 | `pluck*` unnecessary sort + stream when order unused | `triple-store-api PropertyBags.java` 33–52 | Low | Low · Low — sized loop; keep sorted variants where needed |

### iidm — remaining (mostly off this benchmark's critical path)
| ID | Finding | File / location | Impact | Effort · Risk |
|---|---|---|---|---|
| IIDM-D | `AbstractComponent.getBuses()` scans every bus per component (O(components×buses)) | `iidm-api components/AbstractComponent.java` 46–62 | Med (component-walking callers) | Med · Low — bucket buses by component in `AbstractComponentsManager.update()` |
| IIDM-E | Generic `getConnectableStream/Count(Class)` filters the whole identifiable set | `NetworkImpl.java` 955–962, 980–982 | Med (generic-typed callers) | Low · Low — delegate to `index.getAll(clazz)` when indexed |
| IIDM-5 | `getSwitchCount`/`getInternalConnectionCount` via `stream().count()` | `NodeBreakerTopologyModel` 442/898/825 | Low | Low · Low — primitive loop; count null edges without materializing |
| IIDM-11 | `getBusStreamFromBusViewBusId` scans the whole configured→merged mapping | `BusBreakerTopologyModel` 698–704 | Low-med | Med · Low — store reverse `MergedBus→members` index in `BusCache` |
| IIDM-12 | `removeBus` scans all switches (has a `TODO improve` note) | `BusBreakerTopologyModel.removeBus` 788–800 | Low (not a hot path) | Low · Low — use graph adjacency instead of scanning `switches` |

### cgmes — remaining
| ID | Finding | File / location | Impact | Effort · Risk |
|---|---|---|---|---|
| CGMES-9 | `findAssociatedAcLineSegmentCgmesTerminalId` streams all computed terminals per lookup | `TerminalMapping.java` 104–111 | Low-med (bounded to EI boundary terminals) | Med · Low — precompute a node→terminal index |
| CGMES-10 | `injectParams` rebuilds the query via repeated `String.replace` | `CgmesModelTripleStore.injectParams` 797–809 | Low | Low · Low — single-pass build; real win is caching (done, CGMES-A) |
| CGMES-11 | `getBaseVoltageSources`/`getRegions`/`getSubRegions` copy into a new `HashSet` per call | `CgmesExportContext.java` 488–502 | Low (called once/export today) | Low · Low — return `unmodifiableCollection(values())` |

### iidm-serde / iidm-criteria — remaining (SERDE-4/5/6 done, `114a8a4`)
| ID | Finding | File / location | Impact | Effort · Risk |
|---|---|---|---|---|
| SERDE-8 | Per-terminal string concat for indexed attribute names | `ConnectableSerDeUtil.java` (writeNode/Bus/PQ) | Low (high frequency, small strings) | Low · Low — precomputed constants per index |
| SERDE-10 | Compiled `Schema` rebuilt for non-default `ExtensionsSupplier` | `NetworkSerDe.createSchema` 130–183 | Low-med (validation-only) | Med · Low — cache `Schema` by `(supplier,version)` |
| SERDE-11 | `sortedExtensions` allocates stream+list per identifiable | `util/IidmSerDeUtil.java` 429–495 | Low | Low · Low — skip when ≤1 element |
| SERDE-12 | `findRegulatedTerminals` allocates two lists per identifiable at index build | `iidm-modification RegulatedTerminalControllers.java` 41–105 | Low (index build, once) | Low · Low — single list, lazy alloc |

### security / contingency — remaining
| ID | Finding | File / location | Impact | Effort · Risk |
|---|---|---|---|---|
| SEC-5 | `printPostContingencyViolations` applies the filter twice (header count + render) | `Security.java` 423–439, 482–489 | Low-med (CLI/report path) | Low · Low — compute filtered lists once |
| SEC-6 | `toModification` stream/collect intermediate list per apply | `Contingency.java` 88–90 | Low | Low · Low — sized loop |
| SEC-7 | `hashCode` via `Objects.hash` (varargs array + enum boxing) | `ContingencyContext.java` 75–78 | Low (free if hashed per factor) | Low · Low — direct hash; optionally cache |

---

## 5. Suggested next batches

1. ~~**Converters** (CONV-1, CONV-3, CONV-4)~~ — **done** (`9e00cd2`).
2. ~~**Criteria** (SERDE-4/5/6)~~ — **done** (`114a8a4`).
3. ~~**Commons binary**~~ — **done** (CMN-2/3, `f6af75d`). Table formatter
   (CMN-5/CONV-6) and binary `writeString` (CMN-4) **rejected** — see §3.
   allocation, but wants a reusable-buffer / cached-formatter design pass — do as
   one deliberate batch, not piecemeal.
4. Leave StAX-bound (CMN-12) and correctness-sensitive (TS-7) items unless a
   profiler shows them dominating a real workload.

Each batch should be verified against the touched modules' test suites (as the
implemented ones were) and, where a benchmark can exercise it, measured
before/after in the same JVM session.
