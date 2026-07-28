package bench;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Focused XIIDM write/read timing. Args: <mat> <label> */
public final class XiidmBench {
    private static final int WARMUP = 3;
    private static final int RUNS = 10;

    private XiidmBench() {
    }

    private static double median(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        s.sort(Long::compareTo);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    public static void main(String[] args) throws Exception {
        Path mat = Path.of(args[0]);
        String label = args.length > 1 ? args[1] : "?";
        Network network = Network.read(mat);
        ExportOptions options = new ExportOptions();

        for (int i = 0; i < WARMUP; i++) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024 * 1024);
            NetworkSerDe.write(network, options, bos);
        }
        List<Long> wt = new ArrayList<>();
        byte[] xiidm = null;
        for (int i = 0; i < RUNS; i++) {
            System.gc();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024 * 1024);
            long t0 = System.nanoTime();
            NetworkSerDe.write(network, options, bos);
            wt.add((System.nanoTime() - t0) / 1_000_000);
            xiidm = bos.toByteArray();
        }
        System.out.printf("RESULT xiidm-write [%s] median=%7.1f ms  runs=%s  size=%d MiB%n",
                label, median(wt), wt, xiidm.length / (1024 * 1024));

        for (int i = 0; i < WARMUP; i++) {
            NetworkSerDe.read(new ByteArrayInputStream(xiidm));
        }
        List<Long> rt = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            System.gc();
            long t0 = System.nanoTime();
            Network n = NetworkSerDe.read(new ByteArrayInputStream(xiidm));
            rt.add((System.nanoTime() - t0) / 1_000_000);
            if (n == null) {
                throw new IllegalStateException();
            }
        }
        System.out.printf("RESULT xiidm-read  [%s] median=%7.1f ms  runs=%s%n", label, median(rt), rt);
    }
}
