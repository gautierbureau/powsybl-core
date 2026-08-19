package bench;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Isolated variant clone/remove benchmark. Usage: VariantBench <case.mat> <label> */
public final class VariantBench {
    private VariantBench() { }

    static double median(List<Long> v) {
        List<Long> s = new ArrayList<>(v); s.sort(Long::compareTo);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    public static void main(String[] args) throws Exception {
        Network network = Network.read(Path.of(args[0]));
        String label = args.length > 1 ? args[1] : "?";
        // repeated single clone/remove (exercises getStafulObjects per op)
        List<Long> times = new ArrayList<>();
        for (int r = 0; r < 9; r++) {
            long t0 = System.nanoTime();
            VariantManager vm = network.getVariantManager();
            for (int i = 0; i < 50; i++) {
                vm.cloneVariant("InitialState", "b-" + i);
            }
            for (int i = 0; i < 50; i++) {
                vm.removeVariant("b-" + i);
            }
            long t1 = System.nanoTime();
            times.add((t1 - t0) / 1_000_000);
        }
        System.out.printf("RESULT [%s] variant-clone-remove-x50 median=%.1f ms runs=%s%n",
                label, median(times.subList(2, times.size())), times);
    }
}
