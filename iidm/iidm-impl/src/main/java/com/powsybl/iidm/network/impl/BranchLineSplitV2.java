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
import com.powsybl.iidm.network.Network;
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

    /** Endpoint attachment of a half-line's outer terminal: a configured bus, or a node. */
    private record Attach(boolean nodeBreaker, String busId, int node) {
        static Attach bus(String busId) {
            return new Attach(false, busId, -1);
        }

        static Attach node(int node) {
            return new Attach(true, null, node);
        }
    }

    static NetworkImpl split(NetworkImpl base, String branchId, String lineId, double positionPercent,
                             String fictSubId, String fictVlId, String fictBusId, String line1Id, String line2Id) {
        Line baseLine = base.getLine(lineId);
        if (baseLine == null) {
            throw new PowsyblException("Line '" + lineId + "' not found");
        }
        VoltageLevelExt vl1 = (VoltageLevelExt) baseLine.getTerminal1().getVoltageLevel();
        VoltageLevelExt vl2 = (VoltageLevelExt) baseLine.getTerminal2().getVoltageLevel();
        Attach a1 = attachmentOf(baseLine.getTerminal1());
        Attach a2 = attachmentOf(baseLine.getTerminal2());
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

        // 2. fictitious mid-line voltage level at the fault point (branch-owned, bus/breaker)
        Substation sf = branch.newSubstation().setId(fictSubId).setFictitious(true).add();
        VoltageLevel vf = sf.newVoltageLevel().setId(fictVlId).setNominalV(vl1.getNominalV()).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vf.getBusBreakerView().newBus().setId(fictBusId).add();

        // 3. the two half-lines: outer terminals branch-attach onto the shared endpoint VLs (no graph
        //    mutation); inner terminals attach normally onto the branch-owned fictitious VL.
        ThreadLocalBranchContext.run(context, () -> {
            context.beginBranchAttach(List.of(vl1, vl2));
            try {
                addHalfLine(branch, line1Id, vl1.getId(), a1, fictVlId, Attach.bus(fictBusId), r * p, x * p);
                addHalfLine(branch, line2Id, fictVlId, Attach.bus(fictBusId), vl2.getId(), a2, r * (1 - p), x * (1 - p));
            } finally {
                context.endBranchAttach();
            }
        });

        // 4. record the same split as a replayable structural delta on a plain full copy of the base
        //    (for flatten-on-write): really remove the line and really add the fictitious VL + half-lines,
        //    via the public API. Generic flatten = copy(base) + this replay (see BranchFlattenerV2).
        double nominalV = vl1.getNominalV();
        context.recordStructuralDelta(target -> {
            target.getLine(lineId).remove();
            Substation replaySf = target.newSubstation().setId(fictSubId).setFictitious(true).add();
            replaySf.newVoltageLevel().setId(fictVlId).setNominalV(nominalV).setFictitious(true)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId(fictBusId).add();
            addHalfLine(target, line1Id, vl1.getId(), a1, fictVlId, Attach.bus(fictBusId), r * p, x * p);
            addHalfLine(target, line2Id, fictVlId, Attach.bus(fictBusId), vl2.getId(), a2, r * (1 - p), x * (1 - p));
        });
        return branch;
    }

    private static Attach attachmentOf(Terminal t) {
        if (t.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER) {
            return Attach.node(t.getNodeBreakerView().getNode());
        }
        return Attach.bus(t.getBusBreakerView().getConnectableBus().getId());
    }

    private static void addHalfLine(Network n, String id, String vlA, Attach a, String vlB, Attach b,
                                    double r, double x) {
        var adder = n.newLine().setId(id).setVoltageLevel1(vlA).setVoltageLevel2(vlB)
                .setR(r).setX(x).setG1(0).setB1(0).setG2(0).setB2(0);
        if (a.nodeBreaker()) {
            adder.setNode1(a.node());
        } else {
            adder.setConnectableBus1(a.busId()).setBus1(a.busId());
        }
        if (b.nodeBreaker()) {
            adder.setNode2(b.node());
        } else {
            adder.setConnectableBus2(b.busId()).setBus2(b.busId());
        }
        adder.add();
    }

    /** Convenience for assertions: the ids of the connectables a shared VL exposes in the branch view. */
    static List<String> lineIdsInBranchView(VoltageLevel vl) {
        return vl.getConnectableStream(Line.class).map(Line::getId).sorted().toList();
    }

    static boolean terminalOnBus(Terminal t) {
        return t.getBusBreakerView().getBus() != null;
    }
}
