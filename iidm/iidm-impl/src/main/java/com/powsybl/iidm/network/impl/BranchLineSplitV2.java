/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;

import java.util.List;

/**
 * <p><b>Spike v2 — generic (don't-copy) structural branching.</b> See
 * {@code structural-variant-generic-design.md}. De-risking prototype of the shared-VL model: split a
 * line on a structural branch <em>without materialising (deep-copying) either endpoint voltage
 * level</em>.</p>
 *
 * <p>Where v1 ({@link BranchLineSplit}) rebuilds the two endpoint VLs as branch-owned copies — the one
 * source of per-type code — v2 keeps them <b>shared</b> and represents the split as a pure
 * terminal-membership delta over them:</p>
 * <ol>
 *   <li><b>branch-detached</b>: hide the split line's two terminals from the shared endpoint VLs
 *       ({@link BranchContext#detach}); the base graph is untouched, the folds subtract them.</li>
 *   <li><b>branch-attach add</b>: create the two half-lines (branch-owned) whose outer terminals sit on
 *       the shared endpoint VLs, recorded in the context rather than added to the shared graph
 *       ({@link BranchContext#beginBranchAttach}).</li>
 * </ol>
 *
 * <p>Because the endpoint VLs are never copied, everything they host — loads, generators, and any
 * through-line such as {@code M} — simply stays on the shared VL, with zero per-type code and no
 * cascade. This prototype covers bus/breaker endpoints (the shape used to de-risk the two new
 * primitives); node/breaker and the full operation rewrite follow.</p>
 *
 * @author Claude
 */
final class BranchLineSplitV2 {

    private BranchLineSplitV2() {
    }

    static NetworkImpl split(NetworkImpl base, String branchId, String lineId, double positionPercent,
                             String fictSubId, String fictVlId, String fictBusId, String line1Id, String line2Id) {
        Line baseLine = base.getLine(lineId);
        if (baseLine == null) {
            throw new PowsyblException("Line '" + lineId + "' not found");
        }
        VoltageLevelExt vl1 = (VoltageLevelExt) baseLine.getTerminal1().getVoltageLevel();
        VoltageLevelExt vl2 = (VoltageLevelExt) baseLine.getTerminal2().getVoltageLevel();
        if (vl1.getTopologyKind() != TopologyKind.BUS_BREAKER || vl2.getTopologyKind() != TopologyKind.BUS_BREAKER) {
            throw new PowsyblException("splitLine v2 prototype: only bus/breaker endpoints are supported");
        }
        String bus1 = baseLine.getTerminal1().getBusBreakerView().getConnectableBus().getId();
        String bus2 = baseLine.getTerminal2().getBusBreakerView().getConnectableBus().getId();
        double r = baseLine.getR();
        double x = baseLine.getX();
        double p = positionPercent / 100.0;

        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, branchId);
        OverlayNetworkIndex overlay = (OverlayNetworkIndex) branch.getIndex();
        BranchContext context = branch.getBranchContext();

        // 1. hide the original line in the branch index, and detach its terminals from the shared endpoints
        overlay.tombstone(baseLine);
        context.detach((TerminalExt) baseLine.getTerminal1(), vl1);
        context.detach((TerminalExt) baseLine.getTerminal2(), vl2);

        // 2. fictitious mid-line voltage level at the fault point (branch-owned)
        Substation sf = branch.newSubstation().setId(fictSubId).setFictitious(true).add();
        VoltageLevel vf = sf.newVoltageLevel().setId(fictVlId).setNominalV(vl1.getNominalV()).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vf.getBusBreakerView().newBus().setId(fictBusId).add();

        // 3. the two half-lines: outer terminals branch-attach onto the shared endpoint VLs (no graph
        //    mutation); inner terminals attach normally onto the branch-owned fictitious VL.
        ThreadLocalBranchContext.run(context, () -> {
            context.beginBranchAttach(List.of(vl1, vl2));
            try {
                addHalfLine(branch, line1Id, vl1.getId(), bus1, fictVlId, fictBusId, r * p, x * p);
                addHalfLine(branch, line2Id, fictVlId, fictBusId, vl2.getId(), bus2, r * (1 - p), x * (1 - p));
            } finally {
                context.endBranchAttach();
            }
        });
        return branch;
    }

    private static void addHalfLine(NetworkImpl n, String id, String vlA, String busA, String vlB, String busB,
                                    double r, double x) {
        n.newLine().setId(id).setVoltageLevel1(vlA).setVoltageLevel2(vlB)
                .setConnectableBus1(busA).setBus1(busA).setConnectableBus2(busB).setBus2(busB)
                .setR(r).setX(x).setG1(0).setB1(0).setG2(0).setB2(0)
                .add();
    }

    /** Convenience for assertions: the ids of the connectables a shared VL exposes in the branch view. */
    static List<String> lineIdsInBranchView(VoltageLevel vl) {
        return vl.getConnectableStream(Line.class).map(Line::getId).sorted().toList();
    }

    static boolean terminalOnBus(Terminal t) {
        return t.getBusBreakerView().getBus() != null;
    }
}
