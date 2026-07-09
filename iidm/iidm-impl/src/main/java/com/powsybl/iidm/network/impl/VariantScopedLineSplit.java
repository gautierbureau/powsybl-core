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

import java.util.List;

/**
 * <p><b>Spike v2.1a — turnkey single-network structural split.</b> See
 * {@code structural-variant-generic-design.md}, the v2.1 section. The end-to-end payoff: a fault-on-line
 * split expressed as <b>pure variant state on one network</b>. No side-car index and no ambient
 * context — the "branch" is a cloned variant, and
 * after {@code split(...)} the whole split topology is visible simply by
 * {@code setWorkingVariant(faultedVariantId)}, while the source variant keeps the original network.</p>
 *
 * <p>It records into the variant-scoped layers instead of a context: {@link VariantScopedExistence} hides
 * the split line and makes the new objects exist only in the faulted variant; {@link VariantScopedMembership}
 * detaches the split line's terminals from the shared endpoints and (via the topology-model branch-attach
 * intercept) attaches the half-lines' outer terminals onto them — all in the faulted variant, cloned by
 * the real {@code cloneVariant}. Bus/breaker and node/breaker endpoints are supported.</p>
 *
 * @author Claude
 */
final class VariantScopedLineSplit {

    private VariantScopedLineSplit() {
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

    static void split(NetworkImpl n, String faultedVariantId, String lineId, double positionPercent,
                      String fictSubId, String fictVlId, String fictBusId, String line1Id, String line2Id) {
        Line line = n.getLine(lineId);
        if (line == null) {
            throw new PowsyblException("Line '" + lineId + "' not found");
        }
        VoltageLevelExt vl1 = (VoltageLevelExt) line.getTerminal1().getVoltageLevel();
        VoltageLevelExt vl2 = (VoltageLevelExt) line.getTerminal2().getVoltageLevel();
        TerminalExt t1 = (TerminalExt) line.getTerminal1();
        TerminalExt t2 = (TerminalExt) line.getTerminal2();
        Attach a1 = attachmentOf(line.getTerminal1());
        Attach a2 = attachmentOf(line.getTerminal2());
        double r = line.getR();
        double x = line.getX();
        double p = positionPercent / 100.0;
        double nominalV = vl1.getNominalV();

        VariantScopedExistence existence = n.enableVariantScopedExistence();
        VariantScopedMembership membership = n.enableVariantScopedMembership();

        // the branch is a cloned variant of the current one
        String source = n.getVariantManager().getWorkingVariantId();
        if (!n.getVariantManager().getVariantIds().contains(faultedVariantId)) {
            n.getVariantManager().cloneVariant(source, faultedVariantId);
        }
        n.getVariantManager().setWorkingVariant(faultedVariantId);
        try {
            // hide the split line and detach its terminals from the shared endpoints — in this variant only
            existence.hideInCurrentVariant(lineId);
            membership.detachInCurrentVariant(vl1, t1);
            membership.detachInCurrentVariant(vl2, t2);

            // fictitious mid-line voltage level (branch-owned, bus/breaker)
            Substation sf = n.newSubstation().setId(fictSubId).setFictitious(true).add();
            sf.newVoltageLevel().setId(fictVlId).setNominalV(nominalV).setFictitious(true)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId(fictBusId).add();

            // half-lines: outer terminals branch-attach onto the shared endpoints (recorded in the active
            // variant's membership by the topology-model intercept), inner terminals attach onto Vf normally
            membership.beginAttach(List.of(vl1, vl2));
            try {
                addHalfLine(n, line1Id, vl1.getId(), a1, fictVlId, Attach.bus(fictBusId), r * p, x * p);
                addHalfLine(n, line2Id, fictVlId, Attach.bus(fictBusId), vl2.getId(), a2, r * (1 - p), x * (1 - p));
            } finally {
                membership.endAttach();
            }

            // the branch-owned objects surface only in the faulted variant
            existence.existOnlyInCurrentVariant(fictSubId);
            existence.existOnlyInCurrentVariant(fictVlId);
            existence.existOnlyInCurrentVariant(fictBusId);
            existence.existOnlyInCurrentVariant(line1Id);
            existence.existOnlyInCurrentVariant(line2Id);
        } finally {
            n.getVariantManager().setWorkingVariant(source);
        }
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
}
