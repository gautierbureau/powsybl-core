package com.powsybl.iidm.network.impl;

// In package com.powsybl.iidm.network.impl so it can reach the package-private structural-branch
// spike API (createStructuralBranch, BranchLineSplit, BranchContext, ThreadLocalBranchContext) across
// jars. Answers: can we export the base case and a structural branch (variant) to XIIDM?

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.serde.ExportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class BranchExportProbe {

    private BranchExportProbe() {
    }

    private static Network buildBase() {
        Network n = Network.create("base", "test");
        for (String vl : new String[] {"VLA", "VLB", "VLC"}) {
            var s = n.newSubstation().setId("S_" + vl).add();
            s.newVoltageLevel().setId(vl).setNominalV(400).setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("bus_" + vl).add();
        }
        line(n, "L", "VLA", "bus_VLA", "VLB", "bus_VLB");
        line(n, "M", "VLB", "bus_VLB", "VLC", "bus_VLC");
        n.getVoltageLevel("VLA").newLoad().setId("LDA").setConnectableBus("bus_VLA").setBus("bus_VLA").setP0(1).setQ0(0).add();
        return n;
    }

    private static void line(Network n, String id, String vl1, String b1, String vl2, String b2) {
        n.newLine().setId(id).setVoltageLevel1(vl1).setConnectableBus1(b1).setBus1(b1)
                .setVoltageLevel2(vl2).setConnectableBus2(b2).setBus2(b2)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static String xiidm(Network n) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        NetworkSerDe.write(n, new ExportOptions().setThrowExceptionIfExtensionNotFound(false).setSorted(true), os);
        return os.toString(StandardCharsets.UTF_8);
    }

    private static void summarize(String label, String xml) {
        long vls = xml.split("<iidm:voltageLevel", -1).length - 1;
        long lines = xml.split("<iidm:line", -1).length - 1;
        boolean hasL = xml.contains("id=\"L\"");
        boolean hasL1 = xml.contains("id=\"L1\"");
        boolean hasVf = xml.contains("id=\"Vf\"");
        System.out.printf("%-28s bytes=%-6d VLs=%d lines=%d  L=%b L1=%b Vf=%b%n",
                label, xml.length(), vls, lines, hasL, hasL1, hasVf);
    }

    public static void main(String[] args) {
        NetworkImpl base = (NetworkImpl) buildBase();

        // 1. base case
        try {
            summarize("base", xiidm(base));
        } catch (Exception e) {
            System.out.println("base export FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // build a structural branch: split L, which rebinds the through-line M onto VLB'
        NetworkImpl branch = BranchLineSplit.split(base, "branch", "L", 50.0, "SF", "Vf", "busF", "L1", "L2");
        BranchContext ctx = branch.getBranchContext();

        // 2. branch exported naively (no active branch context)
        try {
            summarize("branch (no context)", xiidm(branch));
        } catch (Exception e) {
            System.out.println("branch (no context) export FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 3. branch exported within its branch context
        final String[] out = new String[1];
        final Exception[] err = new Exception[1];
        ThreadLocalBranchContext.run(ctx, () -> {
            try {
                out[0] = xiidm(branch);
            } catch (Exception e) {
                err[0] = e;
            }
        });
        if (err[0] != null) {
            System.out.println("branch (in context) export FAILED: " + err[0].getClass().getSimpleName() + ": " + err[0].getMessage());
        } else {
            summarize("branch (in context)", out[0]);
        }

        // 4. flatten-on-write, then export the plain flattened network
        String flatXml = null;
        try {
            Network flat = BranchFlattener.flatten(branch);
            flatXml = xiidm(flat);
            summarize("flattened branch", flatXml);
        } catch (Exception e) {
            System.out.println("flattened branch export FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 5. ORACLE: clone the base and apply the same split with the ordinary public API, then export.
        //    The flattened branch should be equivalent to this "copy + modify" network.
        Network oracle = NetworkSerDe.copy(buildBase());
        oracle.getLine("L").remove();
        var sf = oracle.newSubstation().setId("SF").setFictitious(true).add();
        var vf = sf.newVoltageLevel().setId("Vf").setNominalV(400).setFictitious(true)
                .setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add();
        vf.getBusBreakerView().newBus().setId("busF").add();
        halfLine(oracle, "L1", "VLA", "bus_VLA", "Vf", "busF");
        halfLine(oracle, "L2", "Vf", "busF", "VLB", "bus_VLB");
        String oracleXml = xiidm(oracle);
        summarize("oracle (clone + modify)", oracleXml);

        System.out.println("\n===== flattened branch  ==  oracle (clone + modify) ? =====");
        System.out.println(normalize(flatXml).equals(normalize(oracleXml))
                ? "EQUAL after normalisation (network id / caseDate stripped)"
                : "DIFFERENT:\n--- flat ---\n" + normalize(flatXml) + "\n--- oracle ---\n" + normalize(oracleXml));
    }

    private static void halfLine(Network n, String id, String vl1, String b1, String vl2, String b2) {
        n.newLine().setId(id).setVoltageLevel1(vl1).setConnectableBus1(b1).setBus1(b1)
                .setVoltageLevel2(vl2).setConnectableBus2(b2).setBus2(b2)
                .setR(0.5).setX(5).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static String normalize(String xml) {
        if (xml == null) {
            return "(null)";
        }
        // strip the two attributes that legitimately differ: the network id and the case date
        return xml.replaceAll(" id=\"[^\"]*\" caseDate=\"[^\"]*\"", " id=\"n\" caseDate=\"d\"");
    }
}
