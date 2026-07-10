package bench;

import com.powsybl.iidm.network.*;

import java.util.ArrayList;
import java.util.List;

/** Node-breaker, switch-heavy network: measures variant clone/remove where switch topology dominates. */
public final class SwitchBench {
    private SwitchBench() { }

    static double median(List<Long> v) {
        List<Long> s = new ArrayList<>(v); s.sort(Long::compareTo);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static Network build(int nSwitches) {
        Network net = Network.create("bench", "test");
        Substation sub = net.newSubstation().setId("S").add();
        int perVl = 400; // stay under the 1000-node-per-voltage-level limit
        int nVl = Math.max(1, nSwitches / perVl);
        for (int v = 0; v < nVl; v++) {
            VoltageLevel vl = sub.newVoltageLevel().setId("VL" + v).setNominalV(400.0)
                    .setTopologyKind(TopologyKind.NODE_BREAKER).add();
            VoltageLevel.NodeBreakerView nbv = vl.getNodeBreakerView();
            nbv.newBusbarSection().setId("bbs" + v).setNode(0).add();
            for (int i = 0; i < perVl; i++) {
                nbv.newBreaker().setId("br" + v + "_" + i).setNode1(i).setNode2(i + 1).setOpen(i % 2 == 0).add();
            }
        }
        return net;
    }

    public static void main(String[] args) {
        int nSwitches = args.length > 0 ? Integer.parseInt(args[0]) : 40000;
        String label = args.length > 1 ? args[1] : "?";
        Network net = build(nSwitches);
        System.out.println("switches=" + net.getSwitchCount());
        List<Long> times = new ArrayList<>();
        for (int r = 0; r < 9; r++) {
            long t0 = System.nanoTime();
            VariantManager vm = net.getVariantManager();
            for (int i = 0; i < 50; i++) {
                vm.cloneVariant("InitialState", "b-" + i);
            }
            for (int i = 0; i < 50; i++) {
                vm.removeVariant("b-" + i);
            }
            times.add((System.nanoTime() - t0) / 1_000_000);
        }
        System.out.printf("RESULT [%s] switch-clone-remove-x50 median=%.1f ms runs=%s%n",
                label, median(times.subList(2, times.size())), times);
    }
}
