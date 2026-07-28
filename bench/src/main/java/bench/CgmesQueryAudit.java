package bench;

import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesModelFactory;
import com.powsybl.cgmes.model.CgmesNamespace;
import com.powsybl.cgmes.model.triplestore.CgmesModelTripleStore;
import com.powsybl.commons.datasource.DataSource;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.QueryCatalog;
import com.powsybl.triplestore.api.TripleStore;
import com.powsybl.triplestore.api.TripleStoreFactory;
import com.powsybl.triplestore.impl.rdf4j.TripleStoreRDF4J;

import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.explanation.Explanation;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit of the CGMES SPARQL query catalogs:
 *   parse  — static well-definedness: every query in every catalog is parsed by the rdf4j
 *            SPARQL parser (with the runtime prefix block and dummy params injected).
 *   scale  — dynamic scaling: run every parameterless named query on N datasets of increasing
 *            size and report time/rows and the growth exponent (log-log slope) so a query whose
 *            cost grows faster than its data (cartesian blow-up) stands out.
 *   explain <zip> [query...] — rdf4j Timed explanation for the given (or all heavy) queries.
 */
public final class CgmesQueryAudit {

    private CgmesQueryAudit() {
    }

    // The 52 parameterless named queries the conversion code calls (grepped from CgmesModelTripleStore).
    private static final String[] PARAMETERLESS = {
        "fullModels", "modelIds", "modelDates", "version", "boundaryNodes", "baseVoltages",
        "substations", "voltageLevels", "terminals", "connectivityNodes", "topologicalNodes",
        "connectivityNodeContainers", "operationalLimits", "generatingUnits", "busbarSections",
        "switches", "acLineSegments", "equivalentBranches", "seriesCompensators", "transformers",
        "transformerEnds", "ratioTapChangers", "ratioTapChangerTablePoints", "phaseTapChangers",
        "phaseTapChangerTablePoints", "regulatingControls", "energyConsumers", "energySources",
        "shuntCompensators", "equivalentShunts", "nonlinearShuntCompensatorPoints",
        "staticVarCompensators", "synchronousMachinesForUpdate", "synchronousMachinesGenerators",
        "synchronousMachinesCondensers", "asynchronousMachines", "externalNetworkInjections",
        "equivalentInjections", "reactiveCapabilityCurveData", "controlAreas", "tieFlows",
        "svVoltages", "svInjections", "topologicalIslands", "acDcConverters", "dcTerminals",
        "dcLineSegments", "dcSwitches", "dcGrounds", "dcNodes", "grounds", "graph",
    };

    // Dummy parameter values by query name for the static parse check (see the query texts).
    private static Map<String, String[]> paramFixtures() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("numObjectsByType", new String[] {"http://iec.ch/TC57/2013/CIM-schema-cim16#"});
        m.put("allObjectsOfType", new String[] {"ACLineSegment"});
        m.put("countrySourcingActors", new String[] {"France"});
        m.put("sourcingActor", new String[] {"RTE"});
        // update/create: {0}=graph iri body, {1}=subject iri body, {2}=predicate qname, {3}=object, {4}=bool
        String[] five = {"http://g", "http://s", "cim:IdentifiedObject.name", "value", "false"};
        m.put("update", five);
        m.put("create", five);
        return m;
    }

    private static String injectParams(String q, String[] params) {
        String out = q;
        for (int i = 0; i < params.length; i++) {
            out = out.replace("{" + i + "}", params[i]);
        }
        return out;
    }

    private static String prefixBlock(String cimNamespace) {
        // Mirrors AbstractPowsyblTripleStore.adjustedQuery: rdf + cim + entsoe + eu.
        return "prefix rdf: <" + CgmesNamespace.RDF_NAMESPACE + "> "
             + "prefix cim: <" + cimNamespace + "> "
             + "prefix entsoe: <" + CgmesNamespace.ENTSOE_NAMESPACE + "> "
             + "prefix eu: <" + CgmesNamespace.EU_NAMESPACE + "> ";
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "parse" : args[0];
        switch (mode) {
            case "parse" -> parse();
            case "scale" -> scale(java.util.Arrays.copyOfRange(args, 1, args.length));
            case "explain" -> explain(args[1], java.util.Arrays.copyOfRange(args, 2, args.length));
            default -> System.err.println("unknown mode " + mode);
        }
    }

    // ----- static parse check -----

    private static void parse() {
        Map<String, String[]> fixtures = paramFixtures();
        String[][] catalogs = {
            {"CIM16.sparql", CgmesNamespace.CIM_16_NAMESPACE},
            {"CIM16-update.sparql", CgmesNamespace.CIM_16_NAMESPACE},
            {"CIM100.sparql", CgmesNamespace.CIM_100_NAMESPACE},
            {"CIM100-update.sparql", CgmesNamespace.CIM_100_NAMESPACE},
        };
        int total = 0;
        int failed = 0;
        for (String[] c : catalogs) {
            QueryCatalog cat = new QueryCatalog(c[0]);
            String prefixes = prefixBlock(c[1]);
            System.out.println("==== " + c[0] + " (" + cat.size() + " queries) ====");
            List<String> names = new ArrayList<>(cat.keySet());
            names.sort(String::compareTo);
            for (String name : names) {
                total++;
                String text = cat.get(name);
                String[] params = fixtures.get(name);
                boolean parameterized = text.contains("{0}");
                if (params != null) {
                    text = injectParams(text, params);
                }
                String full = prefixes + text;
                boolean isUpdate = looksLikeUpdate(text);
                try {
                    if (isUpdate) {
                        QueryParserUtil.parseUpdate(QueryLanguage.SPARQL, full, null);
                    } else {
                        QueryParserUtil.parseQuery(QueryLanguage.SPARQL, full, null);
                    }
                } catch (Exception e) {
                    // If a residual {n} placeholder remained (param we did not inject) skip: not a real defect.
                    if (text.matches("(?s).*\\{[0-9]\\}.*")) {
                        System.out.printf("  ? %-34s (uninjected param, skipped)%n", name);
                        continue;
                    }
                    failed++;
                    System.out.printf("  FAIL %-30s %s%n", name,
                            e.getMessage().replaceAll("\\s+", " ").trim());
                    continue;
                }
                System.out.printf("  ok   %-30s %s%n", name, parameterized ? "(parameterized)" : "");
            }
            System.out.println();
        }
        System.out.println("Parsed " + total + " queries, " + failed + " failed to parse.");
    }

    private static boolean looksLikeUpdate(String text) {
        String u = text.toUpperCase();
        return u.contains("INSERT ") || u.contains("DELETE ") || u.contains("INSERT{") || u.contains("DELETE{");
    }

    // ----- dynamic scaling -----

    private static void scale(String[] zips) {
        int reps = 3;
        // dataset -> query -> {rows, medianMs}
        List<String> labels = new ArrayList<>();
        List<Map<String, long[]>> perDataset = new ArrayList<>();
        List<Long> tripleCounts = new ArrayList<>();
        for (String zip : zips) {
            Path source = Path.of(zip);
            String label = source.getFileName().toString().replace(".zip", "");
            labels.add(label);
            long t0 = System.currentTimeMillis();
            CgmesModel cgmes = CgmesModelFactory.create(DataSource.fromPath(source), TripleStoreFactory.defaultImplementation());
            long loadMs = System.currentTimeMillis() - t0;
            long triples = tripleCount(cgmes);
            tripleCounts.add(triples);
            System.out.printf("loaded %-16s load=%,d ms  triples=%,d%n", label, loadMs, triples);
            CgmesModelTripleStore cts = (CgmesModelTripleStore) cgmes;
            Map<String, long[]> byQuery = new LinkedHashMap<>();
            for (String q : PARAMETERLESS) {
                long best = Long.MAX_VALUE;
                int rows = -1;
                for (int r = 0; r < reps; r++) {
                    // bypass the parameterless-query cache so we time real execution each rep
                    cts.tripleStore(); // no-op keep-alive
                    long s = System.nanoTime();
                    PropertyBags pb = cts.query(injectedNamed(cts, q));
                    long dt = System.nanoTime() - s;
                    best = Math.min(best, dt);
                    rows = pb.size();
                }
                byQuery.put(q, new long[] {rows, best / 1000}); // micros
            }
            perDataset.add(byQuery);
        }
        printScaleTable(labels, tripleCounts, perDataset);
    }

    // Resolve a named query's text via the catalog + runtime prefixes, no params.
    private static String injectedNamed(CgmesModelTripleStore cts, String name) {
        // CgmesModelTripleStore.query() prepends prefixes through the triple store, so we pass raw text.
        // We reach the catalog text via a fresh QueryCatalog matching the model's CIM version.
        return namedText(cts, name);
    }

    private static QueryCatalog baseCatalog;
    private static QueryCatalog updateCatalog;

    private static String namedText(CgmesModelTripleStore cts, String name) {
        int v = cts.getCimVersion();
        if (baseCatalog == null) {
            baseCatalog = new QueryCatalog("CIM" + v + ".sparql");
            updateCatalog = new QueryCatalog("CIM" + v + "-update.sparql");
        }
        String t = baseCatalog.get(name);
        if (t == null) {
            t = updateCatalog.get(name);
        }
        return t == null ? "SELECT * { ?s ?p ?o } LIMIT 0" : t;
    }

    private static long tripleCount(CgmesModel cgmes) {
        try {
            PropertyBags pb = ((CgmesModelTripleStore) cgmes).query("SELECT (COUNT(*) AS ?n) { GRAPH ?g { ?s ?p ?o } }");
            if (!pb.isEmpty()) {
                return Long.parseLong(pb.get(0).get("n"));
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }

    private static void printScaleTable(List<String> labels, List<Long> triples, List<Map<String, long[]>> data) {
        System.out.println();
        System.out.print(String.format("%-34s", "query"));
        for (String l : labels) {
            System.out.print(String.format("%14s", l + " rows"));
        }
        for (String l : labels) {
            System.out.print(String.format("%13s", l + " us"));
        }
        System.out.println(String.format("%10s", "exp"));
        // scaling exponent = slope of log(time) vs log(rows) between first non-empty and last dataset
        for (String q : PARAMETERLESS) {
            long[] first = data.get(0).get(q);
            long[] last = data.get(data.size() - 1).get(q);
            // Skip queries empty on all datasets (not exercised by these bus-branch cases)
            boolean anyRows = data.stream().anyMatch(d -> d.get(q)[0] > 0);
            if (!anyRows) {
                continue;
            }
            System.out.print(String.format("%-34s", q));
            for (Map<String, long[]> d : data) {
                System.out.print(String.format("%14d", d.get(q)[0]));
            }
            for (Map<String, long[]> d : data) {
                System.out.print(String.format("%13d", d.get(q)[1]));
            }
            // exponent vs rows across the largest span with rows>0
            double exp = expByRows(data, q);
            System.out.println(String.format("%10s", Double.isNaN(exp) ? "-" : String.format("%.2f", exp)));
        }
        System.out.println();
        System.out.println("exp = d(log time)/d(log rows) between smallest and largest non-empty dataset.");
        System.out.println("~1.0 linear in result size; >1.3 is worth an EXPLAIN (superlinear vs its own output).");
        // also report time growth vs triples for the big movers
        System.out.println();
        System.out.println("Total query time per dataset (sum of all parameterless queries, best-of-" + 3 + "):");
        for (int i = 0; i < labels.size(); i++) {
            long sum = 0;
            for (String q : PARAMETERLESS) {
                sum += data.get(i).get(q)[1];
            }
            System.out.printf("  %-16s triples=%,-10d totalQuery=%,d us%n", labels.get(i), triples.get(i), sum);
        }
    }

    private static double expByRows(List<Map<String, long[]>> data, String q) {
        // first and last dataset with rows>0 and time>0
        int lo = -1;
        int hi = -1;
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).get(q)[0] > 0 && data.get(i).get(q)[1] > 0) {
                if (lo < 0) {
                    lo = i;
                }
                hi = i;
            }
        }
        if (lo < 0 || hi == lo) {
            return Double.NaN;
        }
        double r0 = data.get(lo).get(q)[0];
        double r1 = data.get(hi).get(q)[0];
        double t0 = data.get(lo).get(q)[1];
        double t1 = data.get(hi).get(q)[1];
        if (r1 <= r0) {
            return Double.NaN;
        }
        return Math.log(t1 / t0) / Math.log(r1 / r0);
    }

    // ----- rdf4j Timed explanation -----

    private static void explain(String zip, String[] queryNames) {
        Path source = Path.of(zip);
        CgmesModel cgmes = CgmesModelFactory.create(DataSource.fromPath(source), TripleStoreFactory.defaultImplementation());
        CgmesModelTripleStore cts = (CgmesModelTripleStore) cgmes;
        TripleStore ts = cts.tripleStore();
        Repository repo = ((TripleStoreRDF4J) ts).getRepository();
        String prefixes = prefixBlock(cts.getCimNamespace());
        String[] targets = queryNames.length > 0 ? queryNames
                : new String[] {"terminals", "transformerEnds", "acLineSegments", "operationalLimits", "regulatingControls"};
        try (RepositoryConnection conn = repo.getConnection()) {
            for (String name : targets) {
                String text = namedText(cts, name);
                if (text == null) {
                    System.out.println("== " + name + ": not in catalog ==");
                    continue;
                }
                System.out.println("======== EXPLAIN " + name + " ========");
                try {
                    TupleQuery tq = conn.prepareTupleQuery(QueryLanguage.SPARQL, prefixes + text);
                    Explanation ex = tq.explain(Explanation.Level.Timed);
                    System.out.println(ex);
                } catch (Exception e) {
                    System.out.println("  explain failed: " + e.getMessage());
                }
                System.out.println();
            }
        }
    }
}
