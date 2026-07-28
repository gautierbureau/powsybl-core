package bench;

import com.powsybl.iidm.network.Network;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.util.List;

/**
 * Measures RAM consumption during a CGMES import (e.g. PEGASE-13k):
 *  - allocation churn (total bytes allocated across all threads, com.sun ThreadMXBean)
 *  - peak heap reached during the import (heap pool peak usage, reset before each rep)
 *  - retained heap with the network alive, and after dropping it
 *  - a class histogram (jcmd GC.class_histogram) while the network is alive
 *
 * Usage: java ... bench.CgmesMemBench <cgmes.zip|dir> [reps] [warmup:true|false]
 */
public final class CgmesMemBench {

    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private CgmesMemBench() {
    }

    private static long usedHeapAfterGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            sleep(250);
        }
        return MEMORY.getHeapMemoryUsage().getUsed();
    }

    private static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    private static long peakHeap() {
        long sum = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                sum += pool.getPeakUsage().getUsed();
            }
        }
        return sum;
    }

    private static String mb(long bytes) {
        return String.format("%,.1f MB", bytes / 1048576.0);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Network read(Path source) {
        return Network.read(source);
    }

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]);
        int reps = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        boolean warmup = args.length > 2 && Boolean.parseBoolean(args[2]);

        System.out.println("=== CGMES memory bench ===");
        System.out.println("source   : " + source + "  (" + mb(java.nio.file.Files.size(source)) + " compressed)");
        System.out.println("max heap : " + mb(Runtime.getRuntime().maxMemory()));
        System.out.println("gc       : " + gcNames());

        if (warmup) {
            System.out.println("warmup import (discarded)...");
            Network w = read(source);
            w.getVariantManager().getWorkingVariantId();
            w = null;
            usedHeapAfterGc();
        }

        long baseline = usedHeapAfterGc();
        System.out.println("baseline heap used: " + mb(baseline));
        System.out.println();

        for (int r = 0; r < reps; r++) {
            resetHeapPeaks();
            long allocBefore = THREADS.getTotalThreadAllocatedBytes();
            long t0 = System.nanoTime();

            Network network = read(source);

            long importMs = (System.nanoTime() - t0) / 1_000_000;
            long churn = THREADS.getTotalThreadAllocatedBytes() - allocBefore;
            long peak = peakHeap();

            long retainedAlive = usedHeapAfterGc();

            System.out.printf("--- rep %d ---%n", r);
            System.out.println("  import time            : " + importMs + " ms");
            System.out.println("  allocation churn       : " + mb(churn) + "  (" + String.format("%.1fx", (double) churn / java.nio.file.Files.size(source)) + " compressed input)");
            System.out.println("  peak heap during import: " + mb(peak) + "  (+" + mb(peak - baseline) + " over baseline)");
            System.out.println("  retained (network live): +" + mb(retainedAlive - baseline));
            System.out.println("  network                : substations=" + network.getSubstationCount()
                    + " voltageLevels=" + network.getVoltageLevelCount()
                    + " buses(bus/breaker)=" + network.getBusBreakerView().getBusStream().count());

            if (r == reps - 1) {
                System.out.println();
                classHistogram(30);
            }

            network = null;
            long afterDrop = usedHeapAfterGc();
            System.out.println("  retained after drop    : +" + mb(afterDrop - baseline));
            System.out.println();
        }
    }

    private static String gcNames() {
        StringBuilder sb = new StringBuilder();
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append(gc.getName()).append(' ');
        }
        return sb.toString().trim();
    }

    private static void classHistogram(int topN) {
        try {
            long pid = ProcessHandle.current().pid();
            Process p = new ProcessBuilder("jcmd", Long.toString(pid), "GC.class_histogram")
                    .redirectErrorStream(true).start();
            List<String> lines = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream())).lines().toList();
            p.waitFor();
            System.out.println("  top " + topN + " classes by retained size (network live):");
            int printed = 0;
            for (String line : lines) {
                if (line.contains("#instances") || line.matches("\\s*\\d+:.*")) {
                    System.out.println("    " + line.trim());
                    if (line.matches("\\s*\\d+:.*") && ++printed >= topN) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  (class histogram unavailable: " + e + ")");
        }
    }
}
