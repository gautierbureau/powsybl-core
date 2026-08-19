/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.BusbarSection;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.GeneratorAdder;
import com.powsybl.iidm.network.InjectionAdder;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.LineAdder;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.LoadAdder;
import com.powsybl.iidm.network.MinMaxReactiveLimits;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.Terminal;
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
 * into the branch (its own adders rebuild them as branch-owned copies, shadowing the base). Endpoint
 * <em>injections</em> (loads, generators) are re-homed into the branch copies; an endpoint hosting
 * another <em>through-connectable</em> (a second line/transformer) is not recreated — only its near
 * terminal is rebound onto the branch copy (via the {@link BranchContext}), leaving the far side
 * untouched so nothing cascades. Both bus/breaker and node/breaker endpoints are supported.</p>
 *
 * @author Claude
 */
final class BranchLineSplit {

    private BranchLineSplit() {
    }

    /** Endpoint attachment of a half-line: a configured bus (bus/breaker) or a node (node/breaker). */
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
        VoltageLevel vl1 = baseLine.getTerminal1().getVoltageLevel();
        VoltageLevel vl2 = baseLine.getTerminal2().getVoltageLevel();
        Attach a1 = attachmentOf(baseLine.getTerminal1());
        Attach a2 = attachmentOf(baseLine.getTerminal2());
        double r = baseLine.getR();
        double x = baseLine.getX();
        double p = positionPercent / 100.0;

        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, branchId);
        OverlayNetworkIndex overlay = (OverlayNetworkIndex) branch.getIndex();

        // 1. copy-on-write materialise the dirty region (both endpoints) as branch-owned copies;
        //    through-connectables at an endpoint are rebound onto the branch copy (no cascade)
        Set<String> materializedSubs = new HashSet<>();
        BranchContext context = branch.getBranchContext();
        overlay.beginMaterialize();
        materializeVoltageLevel(branch, vl1, lineId, materializedSubs, context);
        materializeVoltageLevel(branch, vl2, lineId, materializedSubs, context);
        overlay.endMaterialize();

        // 2. hide the original line in the branch (the base still has it)
        overlay.tombstone(baseLine);

        // 3. fictitious mid-line voltage level at the fault point (bus/breaker, one bus)
        Substation sf = branch.newSubstation().setId(fictSubId).setFictitious(true).add();
        VoltageLevel vf = sf.newVoltageLevel().setId(fictVlId).setNominalV(vl1.getNominalV()).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vf.getBusBreakerView().newBus().setId(fictBusId).add();

        // 4. two half-lines, impedance split at positionPercent, attached where the split line was
        addHalfLine(branch, line1Id, vl1.getId(), a1, fictVlId, Attach.bus(fictBusId), r * p, x * p);
        addHalfLine(branch, line2Id, fictVlId, Attach.bus(fictBusId), vl2.getId(), a2, r * (1 - p), x * (1 - p));
        return branch;
    }

    private static Attach attachmentOf(Terminal t) {
        if (t.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER) {
            return Attach.node(t.getNodeBreakerView().getNode());
        }
        return Attach.bus(t.getBusBreakerView().getConnectableBus().getId());
    }

    private static void addHalfLine(Network n, String id, String vlA, Attach a, String vlB, Attach b, double r, double x) {
        LineAdder adder = n.newLine().setId(id).setVoltageLevel1(vlA).setVoltageLevel2(vlB)
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

    private static void materializeVoltageLevel(NetworkImpl branch, VoltageLevel baseVl, String skipLineId,
                                                Set<String> materializedSubs, BranchContext context) {
        Substation baseSub = baseVl.getSubstation()
                .orElseThrow(() -> new PowsyblException("splitLine spike: voltage level " + baseVl.getId()
                        + " has no substation"));
        Substation subB;
        if (materializedSubs.add(baseSub.getId())) {
            subB = branch.newSubstation().setId(baseSub.getId()).setFictitious(baseSub.isFictitious()).add();
        } else {
            subB = branch.getSubstation(baseSub.getId());
        }
        boolean nodeBreaker = baseVl.getTopologyKind() == TopologyKind.NODE_BREAKER;
        VoltageLevel vlB = subB.newVoltageLevel().setId(baseVl.getId()).setNominalV(baseVl.getNominalV())
                .setFictitious(baseVl.isFictitious()).setTopologyKind(baseVl.getTopologyKind()).add();
        if (nodeBreaker) {
            copyNodeBreakerTopology(baseVl, vlB);
        } else {
            copyBusBreakerTopology(baseVl, vlB);
        }
        for (Connectable<?> c : baseVl.getConnectables()) {
            if (c.getId().equals(skipLineId) || c instanceof BusbarSection) {
                continue; // the split line is replaced by the half-lines; busbars were copied above
            }
            if (c instanceof Load load) {
                copyLoad(vlB, load);
            } else if (c instanceof Generator generator) {
                copyGenerator(vlB, generator);
            } else if (c instanceof Branch<?> throughBranch) {
                // A through-connectable: do not recreate it or touch its far side. Rebind only its near
                // terminal onto the branch copy; the branch view is then correct under the branch
                // context, with no cascade.
                Terminal near = throughBranch.getTerminal1().getVoltageLevel() == baseVl
                        ? throughBranch.getTerminal1() : throughBranch.getTerminal2();
                Integer node = nodeBreaker ? near.getNodeBreakerView().getNode() : null;
                context.rebind((TerminalExt) near, (VoltageLevelExt) vlB, node);
            } else {
                throw new PowsyblException("splitLine spike: endpoint " + baseVl.getId()
                        + " hosts a connectable not yet supported for branch materialisation: " + c.getId()
                        + " (" + c.getClass().getSimpleName() + ")");
            }
        }
    }

    private static void copyBusBreakerTopology(VoltageLevel baseVl, VoltageLevel vlB) {
        for (Bus bus : baseVl.getBusBreakerView().getBuses()) {
            vlB.getBusBreakerView().newBus().setId(bus.getId()).add();
        }
        for (Switch sw : baseVl.getBusBreakerView().getSwitches()) {
            vlB.getBusBreakerView().newSwitch().setId(sw.getId())
                    .setBus1(baseVl.getBusBreakerView().getBus1(sw.getId()).getId())
                    .setBus2(baseVl.getBusBreakerView().getBus2(sw.getId()).getId())
                    .setOpen(sw.isOpen()).add();
        }
    }

    private static void copyNodeBreakerTopology(VoltageLevel baseVl, VoltageLevel vlB) {
        for (BusbarSection bbs : baseVl.getNodeBreakerView().getBusbarSections()) {
            vlB.getNodeBreakerView().newBusbarSection().setId(bbs.getId())
                    .setNode(bbs.getTerminal().getNodeBreakerView().getNode()).add();
        }
        for (Switch sw : baseVl.getNodeBreakerView().getSwitches()) {
            vlB.getNodeBreakerView().newSwitch().setId(sw.getId())
                    .setNode1(baseVl.getNodeBreakerView().getNode1(sw.getId()))
                    .setNode2(baseVl.getNodeBreakerView().getNode2(sw.getId()))
                    .setKind(sw.getKind()).setOpen(sw.isOpen()).setRetained(sw.isRetained()).add();
        }
    }

    private static void copyLoad(VoltageLevel vlB, Load load) {
        LoadAdder adder = vlB.newLoad().setId(load.getId())
                .setLoadType(load.getLoadType()).setP0(load.getP0()).setQ0(load.getQ0());
        attach(adder, load.getTerminal());
        adder.add();
    }

    private static void copyGenerator(VoltageLevel vlB, Generator g) {
        GeneratorAdder adder = vlB.newGenerator().setId(g.getId())
                .setEnergySource(g.getEnergySource())
                .setMinP(g.getMinP()).setMaxP(g.getMaxP())
                .setTargetP(g.getTargetP()).setTargetV(g.getTargetV()).setTargetQ(g.getTargetQ())
                .setVoltageRegulatorOn(g.isVoltageRegulatorOn());
        if (!Double.isNaN(g.getRatedS())) {
            adder.setRatedS(g.getRatedS());
        }
        attach(adder, g.getTerminal());
        Generator gB = adder.add();
        // Copy min/max reactive limits (the short-circuit-relevant common case); a reactive capability
        // curve would need per-point copy, out of scope for this slice.
        if (g.getReactiveLimits() instanceof MinMaxReactiveLimits limits) {
            gB.newMinMaxReactiveLimits().setMinQ(limits.getMinQ()).setMaxQ(limits.getMaxQ()).add();
        }
    }

    /** Attach an injection adder where its base terminal was — by node (node/breaker) or bus (bus/breaker). */
    private static void attach(InjectionAdder<?, ?> adder, Terminal baseTerminal) {
        if (baseTerminal.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER) {
            adder.setNode(baseTerminal.getNodeBreakerView().getNode());
        } else {
            String bus = baseTerminal.getBusBreakerView().getConnectableBus().getId();
            adder.setConnectableBus(bus).setBus(bus);
        }
    }
}
