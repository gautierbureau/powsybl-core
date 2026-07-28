package bench;

import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesModelFactory;
import com.powsybl.commons.datasource.DataSource;
import com.powsybl.triplestore.api.PropertyBags;
import com.powsybl.triplestore.api.TripleStoreFactory;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Loads a CGMES model (triple store only, no IIDM conversion) and reports:
 *  - retained heap of the loaded triple store (the StAX-rewrite target)
 *  - row count and materialized size of the biggest named query results (the streaming-API target)
 */
public final class CgmesQueryStats {

    private CgmesQueryStats() {
    }

    private static long usedHeapAfterGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static String mb(long b) {
        return String.format("%,.1f MB", b / 1048576.0);
    }

    public static void main(String[] args) {
        Path source = Path.of(args[0]);
        long baseline = usedHeapAfterGc();

        long t0 = System.currentTimeMillis();
        CgmesModel cgmes = CgmesModelFactory.create(DataSource.fromPath(source), TripleStoreFactory.defaultImplementation());
        long loadMs = System.currentTimeMillis() - t0;
        long afterLoad = usedHeapAfterGc();
        System.out.println("triple store load: " + loadMs + " ms, retained store = " + mb(afterLoad - baseline));
        System.out.println();

        Map<String, Supplier<PropertyBags>> queries = new LinkedHashMap<>();
        queries.put("terminals", cgmes::terminals);
        queries.put("acLineSegments", cgmes::acLineSegments);
        queries.put("energyConsumers", cgmes::energyConsumers);
        queries.put("synchronousMachinesGenerators", cgmes::synchronousMachinesGenerators);
        queries.put("transformers", cgmes::transformers);
        queries.put("transformerEnds", cgmes::transformerEnds);
        queries.put("switches", cgmes::switches);
        queries.put("shuntCompensators", cgmes::shuntCompensators);
        queries.put("topologicalNodes", cgmes::topologicalNodes);
        queries.put("baseVoltages", cgmes::baseVoltages);
        queries.put("operationalLimits", cgmes::operationalLimits);
        queries.put("ratioTapChangers", cgmes::ratioTapChangers);

        System.out.printf("%-32s %10s %8s %14s%n", "query", "rows", "cols", "retained");
        long totalRetained = 0;
        for (Map.Entry<String, Supplier<PropertyBags>> e : queries.entrySet()) {
            long before = usedHeapAfterGc();
            PropertyBags r;
            try {
                r = e.getValue().get();
            } catch (Exception ex) {
                System.out.printf("%-32s %10s%n", e.getKey(), "n/a (" + ex.getClass().getSimpleName() + ")");
                continue;
            }
            long after = usedHeapAfterGc();
            long retained = Math.max(0, after - before);
            totalRetained += retained;
            int cols = r.isEmpty() ? 0 : r.get(0).propertyNames().size();
            System.out.printf("%-32s %,10d %8d %14s%n", e.getKey(), r.size(), cols, mb(retained));
        }
        System.out.println();
        System.out.println("sum of individually-retained results: " + mb(totalRetained)
                + "  (each measured alive, GC'd between queries; query cache holds them once fetched)");
    }
}
