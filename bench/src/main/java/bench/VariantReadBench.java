package bench;

import com.powsybl.commons.datasource.DirectoryDataSource;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManagerConstants;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stress VariantArray.get() via variant-scoped topology reads (getBusView().getBus() per terminal),
 * both single-threaded and multi-threaded (each thread on its own pre-allocated variant).
 * Args: <mat> <label> [threads]
 */
public final class VariantReadBench {
    private static final int WARMUP = 3;
    private static final int RUNS = 8;
    private static final int SWEEPS = 6;

    private VariantReadBench() {
    }

    private static double median(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        s.sort(Long::compareTo);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    private static long sweep(Network network) {
        long acc = 0;
        for (int s = 0; s < SWEEPS; s++) {
            // bus-view and bus-breaker-view bus resolution route through the topology models'
            // VariantArray.get() (per-variant BusCache lookup)
            for (Bus b : network.getBusView().getBuses()) {
                acc += b.getConnectedTerminalCount();
            }
            for (Bus b : network.getBusBreakerView().getBuses()) {
                acc += b.getConnectedTerminalCount();
            }
        }
        return acc;
    }

    public static void main(String[] args) throws Exception {
        Path mat = Path.of(args[0]);
        String label = args.length > 1 ? args[1] : "?";
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 1;

        Network network = Network.read(mat);

        if (threads <= 1) {
            for (int i = 0; i < WARMUP; i++) {
                sweep(network);
            }
            List<Long> times = new ArrayList<>();
            long sink = 0;
            for (int i = 0; i < RUNS; i++) {
                System.gc();
                long t0 = System.nanoTime();
                sink += sweep(network);
                times.add((System.nanoTime() - t0) / 1_000_000);
            }
            System.out.printf("RESULT variant-topology-read [%s] 1-thread median=%8.1f ms  runs=%s (sink=%d)%n",
                    label, median(times), times, sink);
        } else {
            // Pre-allocate one variant per thread, then read concurrently (each thread its own variant).
            network.getVariantManager().allowVariantMultiThreadAccess(true);
            List<String> variantIds = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String id = "v" + i;
                network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, id);
                variantIds.add(id);
            }
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            // warmup
            for (int w = 0; w < WARMUP; w++) {
                runParallel(network, pool, variantIds);
            }
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < RUNS; i++) {
                System.gc();
                long t0 = System.nanoTime();
                runParallel(network, pool, variantIds);
                times.add((System.nanoTime() - t0) / 1_000_000);
            }
            pool.shutdown();
            System.out.printf("RESULT variant-topology-read [%s] %d-thread median=%8.1f ms  runs=%s%n",
                    label, threads, median(times), times);
        }
    }

    private static void runParallel(Network network, ExecutorService pool, List<String> variantIds) throws Exception {
        List<Callable<Long>> tasks = new ArrayList<>();
        for (String vid : variantIds) {
            tasks.add(() -> {
                network.getVariantManager().setWorkingVariant(vid);
                return sweep(network);
            });
        }
        long acc = 0;
        for (Future<Long> f : pool.invokeAll(tasks)) {
            acc += f.get();
        }
        if (acc == Long.MIN_VALUE) {
            throw new IllegalStateException();
        }
    }
}
