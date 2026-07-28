package bench;

import com.powsybl.commons.datasource.DirectoryDataSource;
import com.powsybl.iidm.criteria.RegexCriterion;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Micro/meso benchmark of powsybl-core hot paths on the PEGASE 13659 case.
 * Usage: Bench <case13659pegase.mat> <workDir> <label>
 */
public final class Bench {

    private static final int WARMUP = 2;
    private static final int RUNS = 7;

    private Bench() {
    }

    interface Op {
        Object run() throws Exception;
    }

    private static double median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static void measure(String name, Op op) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            op.run();
        }
        List<Long> times = new ArrayList<>();
        Object sink = null;
        for (int i = 0; i < RUNS; i++) {
            System.gc();
            long t0 = System.nanoTime();
            sink = op.run();
            long t1 = System.nanoTime();
            times.add((t1 - t0) / 1_000_000);
        }
        System.out.printf("RESULT %-28s median=%8.1f ms   runs=%s   (sink=%s)%n",
                name, median(times), times, sink == null ? "null" : sink.getClass().getSimpleName());
    }

    public static void main(String[] args) throws Exception {
        Path matFile = Path.of(args[0]);
        Path workDir = Path.of(args[1]);
        String label = args.length > 2 ? args[2] : "?";
        Files.createDirectories(workDir);

        System.out.println("=== powsybl bench [" + label + "] on " + matFile.getFileName() + " ===");

        // 1. MATPOWER import (network construction in iidm-impl)
        measure("matpower-import", () -> Network.read(matFile));

        Network network = Network.read(matFile);
        System.out.println("network: " + network.getBusBreakerView().getBusCount() + " buses, "
                + network.getGeneratorCount() + " generators, " + network.getLineCount() + " lines, "
                + network.getTwoWindingsTransformerCount() + " 2wt");

        // 2. XIIDM XML export
        ExportOptions exportOptions = new ExportOptions();
        measure("xiidm-write", () -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024 * 1024);
            NetworkSerDe.write(network, exportOptions, bos);
            return bos.size();
        });

        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024 * 1024);
        NetworkSerDe.write(network, exportOptions, bos);
        byte[] xiidm = bos.toByteArray();
        System.out.println("xiidm size: " + xiidm.length / (1024 * 1024) + " MiB");

        // 3. XIIDM XML import
        measure("xiidm-read", () -> NetworkSerDe.read(new ByteArrayInputStream(xiidm)));

        // 4. Variant clone + remove (50 variants)
        measure("variant-clone-remove-x50", () -> {
            VariantManager vm = network.getVariantManager();
            for (int i = 0; i < 50; i++) {
                vm.cloneVariant("InitialState", "bench-" + i);
            }
            for (int i = 0; i < 50; i++) {
                vm.removeVariant("bench-" + i);
            }
            return null;
        });

        // 5. Bus getP/getQ sweep over the whole network (x20)
        measure("bus-getP-getQ-sweep-x20", () -> {
            double sum = 0;
            for (int i = 0; i < 20; i++) {
                for (Bus b : network.getBusBreakerView().getBuses()) {
                    double p = b.getP();
                    double q = b.getQ();
                    if (!Double.isNaN(p)) {
                        sum += p;
                    }
                    if (!Double.isNaN(q)) {
                        sum += q;
                    }
                }
            }
            return sum;
        });

        // 6. Regex criterion evaluated over all identifiables (x20)
        measure("regex-criterion-x20", () -> {
            RegexCriterion criterion = new RegexCriterion(".*[13579]$");
            long count = 0;
            for (int i = 0; i < 20; i++) {
                for (Identifiable<?> identifiable : network.getIdentifiables()) {
                    if (criterion.filter(identifiable, identifiable.getType())) {
                        count++;
                    }
                }
            }
            return count;
        });

        // 7. CGMES full export (EQ + TP + SSH + SV)
        Path cgmesDir = workDir.resolve("cgmes");
        Files.createDirectories(cgmesDir);
        measure("cgmes-export", () -> {
            network.write("CGMES", new Properties(), new DirectoryDataSource(cgmesDir, "pegase"));
            return null;
        });

        // 8. CGMES import
        measure("cgmes-import", () -> Network.read(new DirectoryDataSource(cgmesDir, "pegase")));

        System.out.println("=== done [" + label + "] ===");
        System.out.println("files: " + Arrays.toString(cgmesDir.toFile().list()));
    }
}
