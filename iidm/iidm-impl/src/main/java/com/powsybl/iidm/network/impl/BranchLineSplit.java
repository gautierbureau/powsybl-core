/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;

import java.util.HashSet;
import java.util.Set;

/**
 * <p><b>Spike phase 3 — structural variant / copy-on-write branching.</b> Reusable operation: split a
 * line at a fictitious mid-line voltage level <em>on a structural branch</em>, leaving the shared base
 * untouched. See {@code structural-variant-spike.md}.</p>
 *
 * <p>The dirty region — the two endpoint voltage levels and the line — is copy-on-write materialised
 * into the branch (its own adders rebuild them as branch-owned copies, shadowing the base). The split
 * then runs against those branch-owned objects. Endpoint <em>injections</em> (here: loads) are
 * re-homed into the branch copies; an endpoint hosting another <em>through-connectable</em> (a second
 * line/transformer) is rejected, because materialising it would cascade into its far voltage level —
 * the general reference-rebinding problem, out of scope for this slice.</p>
 *
 * @author Claude
 */
final class BranchLineSplit {

    private BranchLineSplit() {
    }

    static NetworkImpl split(NetworkImpl base, String branchId, String lineId, double positionPercent,
                             String fictSubId, String fictVlId, String fictBusId, String line1Id, String line2Id) {
        Line baseLine = base.getLine(lineId);
        if (baseLine == null) {
            throw new PowsyblException("Line '" + lineId + "' not found");
        }
        VoltageLevel vl1 = baseLine.getTerminal1().getVoltageLevel();
        VoltageLevel vl2 = baseLine.getTerminal2().getVoltageLevel();
        String bus1 = baseLine.getTerminal1().getBusBreakerView().getConnectableBus().getId();
        String bus2 = baseLine.getTerminal2().getBusBreakerView().getConnectableBus().getId();
        double r = baseLine.getR();
        double x = baseLine.getX();
        double p = positionPercent / 100.0;

        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, branchId);
        OverlayNetworkIndex overlay = (OverlayNetworkIndex) branch.getIndex();

        // 1. copy-on-write materialise the dirty region (both endpoints) as branch-owned copies
        Set<String> materializedSubs = new HashSet<>();
        overlay.beginMaterialize();
        materializeVoltageLevel(branch, vl1, lineId, materializedSubs);
        materializeVoltageLevel(branch, vl2, lineId, materializedSubs);
        overlay.endMaterialize();

        // 2. hide the original line in the branch (the base still has it)
        overlay.tombstone(baseLine);

        // 3. fictitious mid-line voltage level at the fault point
        Substation sf = branch.newSubstation().setId(fictSubId).setFictitious(true).add();
        VoltageLevel vf = sf.newVoltageLevel().setId(fictVlId).setNominalV(vl1.getNominalV()).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vf.getBusBreakerView().newBus().setId(fictBusId).add();

        // 4. two half-lines, impedance split at positionPercent
        newLine(branch, line1Id, vl1.getId(), bus1, fictVlId, fictBusId, r * p, x * p);
        newLine(branch, line2Id, fictVlId, fictBusId, vl2.getId(), bus2, r * (1 - p), x * (1 - p));
        return branch;
    }

    private static void materializeVoltageLevel(NetworkImpl branch, VoltageLevel baseVl, String skipLineId,
                                                Set<String> materializedSubs) {
        if (baseVl.getTopologyKind() != TopologyKind.BUS_BREAKER) {
            throw new PowsyblException("splitLine spike: only bus/breaker voltage levels are supported ("
                    + baseVl.getId() + ")");
        }
        Substation baseSub = baseVl.getSubstation()
                .orElseThrow(() -> new PowsyblException("splitLine spike: voltage level " + baseVl.getId()
                        + " has no substation"));
        Substation subB;
        if (materializedSubs.add(baseSub.getId())) {
            subB = branch.newSubstation().setId(baseSub.getId()).setFictitious(baseSub.isFictitious()).add();
        } else {
            subB = branch.getSubstation(baseSub.getId());
        }
        VoltageLevel vlB = subB.newVoltageLevel().setId(baseVl.getId()).setNominalV(baseVl.getNominalV())
                .setFictitious(baseVl.isFictitious()).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        for (Bus bus : baseVl.getBusBreakerView().getBuses()) {
            vlB.getBusBreakerView().newBus().setId(bus.getId()).add();
        }
        for (Switch sw : baseVl.getBusBreakerView().getSwitches()) {
            Bus b1 = baseVl.getBusBreakerView().getBus1(sw.getId());
            Bus b2 = baseVl.getBusBreakerView().getBus2(sw.getId());
            vlB.getBusBreakerView().newSwitch().setId(sw.getId()).setBus1(b1.getId()).setBus2(b2.getId())
                    .setOpen(sw.isOpen()).add();
        }
        for (Connectable<?> c : baseVl.getConnectables()) {
            if (c.getId().equals(skipLineId)) {
                continue; // the line being split is replaced by the two half-lines
            }
            if (c instanceof Load load) {
                copyLoad(vlB, load);
            } else {
                throw new PowsyblException("splitLine spike: endpoint " + baseVl.getId()
                        + " hosts a connectable not yet supported for branch materialisation: " + c.getId()
                        + " (" + c.getClass().getSimpleName() + ")");
            }
        }
    }

    private static void copyLoad(VoltageLevel vlB, Load load) {
        String bus = load.getTerminal().getBusBreakerView().getConnectableBus().getId();
        vlB.newLoad().setId(load.getId()).setConnectableBus(bus).setBus(bus)
                .setLoadType(load.getLoadType()).setP0(load.getP0()).setQ0(load.getQ0()).add();
    }

    private static void newLine(Network n, String id, String vl1, String b1, String vl2, String b2, double r, double x) {
        n.newLine().setId(id)
                .setVoltageLevel1(vl1).setConnectableBus1(b1).setBus1(b1)
                .setVoltageLevel2(vl2).setConnectableBus2(b2).setBus2(b2)
                .setR(r).setX(x).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }
}
