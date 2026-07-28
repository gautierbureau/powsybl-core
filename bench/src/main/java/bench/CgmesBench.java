package bench;

import com.powsybl.commons.datasource.DirectoryDataSource;
import com.powsybl.iidm.network.Network;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Repeated CGMES import timing. Args: <cgmesDir> <baseName> <label> */
public final class CgmesBench {
    private static final int WARMUP = 2;
    private static final int RUNS = 8;

    private CgmesBench() {
    }

    private static double median(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        s.sort(Long::compareTo);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String base = args[1];
        String label = args.length > 2 ? args[2] : "?";
        for (int i = 0; i < WARMUP; i++) {
            Network.read(new DirectoryDataSource(dir, base));
        }
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            System.gc();
            long t0 = System.nanoTime();
            Network n = Network.read(new DirectoryDataSource(dir, base));
            long t1 = System.nanoTime();
            times.add((t1 - t0) / 1_000_000);
            if (n == null) {
                throw new IllegalStateException();
            }
        }
        System.out.printf("RESULT cgmes-import [%s]  median=%8.1f ms   runs=%s%n", label, median(times), times);
    }
}
