package bench;

import com.powsybl.iidm.modification.topology.ConnectVoltageLevelOnLineBuilder;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.serde.NetworkSerDe;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

/**
 * Go/no-go measurement for the structural-variant "fault on line" spike.
 *
 * A short-circuit fault-on-line study, per (line, position), needs one structural configuration:
 *   split line L at a fictitious VL. Today the only isolation is a full Network copy.
 *
 * This measures the SAVING ENVELOPE of a copy-on-write structural branch:
 *   - cost a study pays TODAY per fault point  = full copy + the split modification
 *   - cost a branch would pay                  = the split modification + tiny overlay overhead
 *   => the copy cost is exactly what branching removes.
 * If full-copy time is small relative to the modification, the design is not worth it.
 */
public final class FaultOnLineBench {

    private FaultOnLineBench() {
    }

    private static long usedHeapAfterGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(200);
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
        Network net = Network.read(source);
        int vlCount = net.getVoltageLevelCount();
        long lineCount = net.getLineStream().count();
        System.out.printf("network %s: %,d VLs, %,d lines%n", net.getId(), vlCount, lineCount);

        // ---- 1. full-copy cost (what a study pays per fault point today) ----
        int reps = 5;
        long bestCopyMs = Long.MAX_VALUE;
        for (int i = 0; i < reps; i++) {
            long t = System.nanoTime();
            Network c = NetworkSerDe.copy(net);
            long dt = (System.nanoTime() - t) / 1_000_000;
            bestCopyMs = Math.min(bestCopyMs, dt);
            if (c.getVoltageLevelCount() != vlCount) {
                throw new IllegalStateException("copy mismatch");
            }
        }
        long base = usedHeapAfterGc();
        Network held = NetworkSerDe.copy(net);
        long afterCopy = usedHeapAfterGc();
        long copyRetained = Math.max(0, afterCopy - base);
        System.out.printf("full copy (NetworkSerDe.copy): best %,d ms over %d reps, retained %s%n",
                bestCopyMs, reps, mb(copyRetained));
        if (held.getLineCount() < 0) {
            System.out.println();
        }

        // ---- 2. the split modification cost alone (a branch still pays this) ----
        // Pick a line to split and prepare a fictitious switching VL with a bus.
        Line line = net.getLines().iterator().next();
        long bestModMs = Long.MAX_VALUE;
        int dirtyVls = -1;
        for (int i = 0; i < reps; i++) {
            Network work = NetworkSerDe.copy(net); // isolate each rep (real study would branch instead)
            Line l = work.getLine(line.getId());
            String fictVlId = prepareFictitiousVl(work);
            String busId = fictVlId + "_BUS";
            int vlsBefore = work.getVoltageLevelCount();
            long t = System.nanoTime();
            new ConnectVoltageLevelOnLineBuilder()
                    .withPositionPercent(50.0)
                    .withBusbarSectionOrBusId(busId)
                    .withLine1Id(l.getId() + "_1")
                    .withLine2Id(l.getId() + "_2")
                    .withLine(l)
                    .build()
                    .apply(work);
            long dt = (System.nanoTime() - t) / 1_000_000;
            bestModMs = Math.min(bestModMs, dt);
            // dirty region: VLs whose membership/content changed = fictitious VL + 2 endpoints (bounded, size-independent)
            dirtyVls = work.getVoltageLevelCount() - vlsBefore + 2;
        }
        System.out.printf("split modification (ConnectVoltageLevelOnLine): best %,d ms%n", bestModMs);
        System.out.printf("dirty region: ~%d voltage levels touched (fictitious + 2 endpoints), out of %,d total%n",
                dirtyVls, vlCount);

        // ---- 3. the envelope ----
        System.out.println();
        System.out.printf("PER FAULT POINT today   = copy %,d ms + mod %,d ms = %,d ms, +%s heap%n",
                bestCopyMs, bestModMs, bestCopyMs + bestModMs, mb(copyRetained));
        System.out.printf("PER FAULT POINT branched= mod %,d ms + O(dirty) overlay ~ %,d ms, +~%d/%,d of the network%n",
                bestModMs, bestModMs, dirtyVls, vlCount);
        System.out.printf("=> branching removes the copy: ~%.1fx less time, and heap ~%.0fx smaller per point%n",
                (double) (bestCopyMs + bestModMs) / Math.max(1, bestModMs),
                (double) vlCount / Math.max(1, dirtyVls));
    }

    /** Create a fictitious substation+VL with one bus, to act as the switching VL. Returns the VL id. */
    private static String prepareFictitiousVl(Network net) {
        String sid = "FICT_S";
        String vid = "FICT_VL";
        Substation s = net.getSubstation(sid);
        if (s == null) {
            s = net.newSubstation().setId(sid).setFictitious(true).add();
        }
        VoltageLevel vl = net.getVoltageLevel(vid);
        if (vl == null) {
            vl = s.newVoltageLevel().setId(vid).setNominalV(400.0).setFictitious(true)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            Bus b = vl.getBusBreakerView().newBus().setId(vid + "_BUS").add();
            if (b == null) {
                throw new IllegalStateException("bus");
            }
        }
        return vid;
    }
}
