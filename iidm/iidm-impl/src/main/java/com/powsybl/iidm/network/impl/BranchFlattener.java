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
import com.powsybl.iidm.network.BusbarSection;
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
import com.powsybl.iidm.network.SubstationAdder;
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;

/**
 * <p><b>Spike — structural variant / copy-on-write branching: flatten-on-write.</b> See
 * {@code structural-variant-cascade-design.md}.</p>
 *
 * <p>A structural branch shares base objects by reference, so it cannot be serialized directly:
 * {@code NetworkSerDe} only writes an element whose {@code getParentNetwork()} is the network being
 * written, and a shared base object's parent is the base — it is silently dropped. To <em>persist</em>
 * a branch, flatten it: rebuild the branch's view (under its context, so rebinds apply) into a plain,
 * self-contained network where every object is owned by that network and thus serializes fully.</p>
 *
 * <p>Spike scope: the connectable types a fault-on-line split produces — buses, busbar sections,
 * switches, loads, generators (core fields + min/max reactive limits) and lines. Any other type
 * present is rejected loudly rather than dropped.</p>
 *
 * @author Claude
 */
final class BranchFlattener {

    private BranchFlattener() {
    }

    static Network flatten(NetworkImpl branch) {
        Network[] out = new Network[1];
        ThreadLocalBranchContext.run(branch.getBranchContext(), () -> out[0] = build(branch));
        return out[0];
    }

    private static Network build(Network branch) {
        Network flat = Network.create(branch.getId(), branch.getSourceFormat());
        for (Substation s : branch.getSubstations()) {
            SubstationAdder adder = flat.newSubstation().setId(s.getId()).setFictitious(s.isFictitious());
            s.getCountry().ifPresent(adder::setCountry);
            Substation fs = adder.add();
            for (VoltageLevel vl : s.getVoltageLevels()) {
                copyTopology(vl, fs.newVoltageLevel().setId(vl.getId()).setNominalV(vl.getNominalV())
                        .setFictitious(vl.isFictitious()).setTopologyKind(vl.getTopologyKind()).add());
            }
        }
        rejectUnsupported(branch);
        for (Load load : branch.getLoads()) {
            copyLoad(flat.getVoltageLevel(load.getTerminal().getVoltageLevel().getId()), load);
        }
        for (Generator g : branch.getGenerators()) {
            copyGenerator(flat.getVoltageLevel(g.getTerminal().getVoltageLevel().getId()), g);
        }
        for (Line line : branch.getLines()) {
            copyLine(flat, line);
        }
        return flat;
    }

    private static void rejectUnsupported(Network branch) {
        int unsupported = branch.getTwoWindingsTransformerCount() + branch.getThreeWindingsTransformerCount()
                + branch.getShuntCompensatorCount() + branch.getStaticVarCompensatorCount()
                + branch.getBatteryCount() + branch.getHvdcLineCount() + branch.getTieLineCount();
        if (unsupported > 0) {
            throw new PowsyblException("flatten spike: the branch contains a connectable type not yet "
                    + "supported for flattening (transformer/shunt/svc/battery/hvdc/tie line)");
        }
    }

    private static void copyTopology(VoltageLevel from, VoltageLevel to) {
        if (from.getTopologyKind() == TopologyKind.NODE_BREAKER) {
            for (BusbarSection bbs : from.getNodeBreakerView().getBusbarSections()) {
                to.getNodeBreakerView().newBusbarSection().setId(bbs.getId())
                        .setNode(bbs.getTerminal().getNodeBreakerView().getNode()).add();
            }
            for (Switch sw : from.getNodeBreakerView().getSwitches()) {
                to.getNodeBreakerView().newSwitch().setId(sw.getId())
                        .setNode1(from.getNodeBreakerView().getNode1(sw.getId()))
                        .setNode2(from.getNodeBreakerView().getNode2(sw.getId()))
                        .setKind(sw.getKind()).setOpen(sw.isOpen()).setRetained(sw.isRetained()).add();
            }
        } else {
            for (Bus bus : from.getBusBreakerView().getBuses()) {
                to.getBusBreakerView().newBus().setId(bus.getId()).add();
            }
            for (Switch sw : from.getBusBreakerView().getSwitches()) {
                to.getBusBreakerView().newSwitch().setId(sw.getId())
                        .setBus1(from.getBusBreakerView().getBus1(sw.getId()).getId())
                        .setBus2(from.getBusBreakerView().getBus2(sw.getId()).getId())
                        .setOpen(sw.isOpen()).add();
            }
        }
    }

    private static void copyLoad(VoltageLevel to, Load load) {
        LoadAdder adder = to.newLoad().setId(load.getId())
                .setLoadType(load.getLoadType()).setP0(load.getP0()).setQ0(load.getQ0());
        attach(adder, load.getTerminal());
        adder.add();
    }

    private static void copyGenerator(VoltageLevel to, Generator g) {
        GeneratorAdder adder = to.newGenerator().setId(g.getId())
                .setEnergySource(g.getEnergySource())
                .setMinP(g.getMinP()).setMaxP(g.getMaxP())
                .setTargetP(g.getTargetP()).setTargetV(g.getTargetV()).setTargetQ(g.getTargetQ())
                .setVoltageRegulatorOn(g.isVoltageRegulatorOn());
        if (!Double.isNaN(g.getRatedS())) {
            adder.setRatedS(g.getRatedS());
        }
        attach(adder, g.getTerminal());
        Generator gB = adder.add();
        if (g.getReactiveLimits() instanceof MinMaxReactiveLimits limits) {
            gB.newMinMaxReactiveLimits().setMinQ(limits.getMinQ()).setMaxQ(limits.getMaxQ()).add();
        }
    }

    private static void copyLine(Network flat, Line line) {
        Terminal t1 = line.getTerminal1();
        Terminal t2 = line.getTerminal2();
        LineAdder adder = flat.newLine().setId(line.getId())
                .setVoltageLevel1(t1.getVoltageLevel().getId()).setVoltageLevel2(t2.getVoltageLevel().getId())
                .setR(line.getR()).setX(line.getX())
                .setG1(line.getG1()).setB1(line.getB1()).setG2(line.getG2()).setB2(line.getB2());
        attachLineSide(adder, 1, t1);
        attachLineSide(adder, 2, t2);
        adder.add();
    }

    private static void attach(InjectionAdder<?, ?> adder, Terminal t) {
        if (t.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER) {
            adder.setNode(t.getNodeBreakerView().getNode());
        } else {
            String bus = t.getBusBreakerView().getConnectableBus().getId();
            adder.setConnectableBus(bus).setBus(bus);
        }
    }

    private static void attachLineSide(LineAdder adder, int side, Terminal t) {
        boolean nodeBreaker = t.getVoltageLevel().getTopologyKind() == TopologyKind.NODE_BREAKER;
        if (side == 1) {
            if (nodeBreaker) {
                adder.setNode1(t.getNodeBreakerView().getNode());
            } else {
                String bus = t.getBusBreakerView().getConnectableBus().getId();
                adder.setConnectableBus1(bus).setBus1(bus);
            }
        } else {
            if (nodeBreaker) {
                adder.setNode2(t.getNodeBreakerView().getNode());
            } else {
                String bus = t.getBusBreakerView().getConnectableBus().getId();
                adder.setConnectableBus2(bus).setBus2(bus);
            }
        }
    }
}
