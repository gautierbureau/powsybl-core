/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.DefaultTopologyVisitor;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bus-side reads of the variant-scoped terminal membership, in bus/breaker topology: equipment added
 * onto (or removed from) a <b>pre-existing shared bus</b> in a structural variant must show through every
 * bus-level read a load-flow engine builds its model from — the bus view and bus-breaker view buses' typed
 * accessors ({@code getGenerators}, {@code getLines}, ...), connected-terminal enumeration/count, and the
 * {@code visitConnectedEquipments} / {@code visitConnectedOrConnectableEquipments} visitors. The fold
 * happens once, in {@code ConfiguredBusImpl}; the merged bus-view bus and every accessor derived from the
 * connected terminals inherit it.
 *
 * <p>(The counterpart case — equipment on a voltage level that itself exists only in the variant — is
 * covered by {@code StructuralVariantSplitViaApiTest}.)</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class StructuralVariantBusViewMembershipTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("bus-membership", "test");
        for (int i = 0; i < 3; i++) {
            Substation s = n.newSubstation().setId("S" + i).add();
            VoltageLevel vl = s.newVoltageLevel().setId("VL" + i).setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl.getBusBreakerView().newBus().setId("B" + i).add();
        }
        n.getVoltageLevel("VL0").newGenerator().setId("GEN0").setBus("B0").setConnectableBus("B0")
                .setMinP(0).setMaxP(100).setTargetP(20).setTargetV(400).setVoltageRegulatorOn(true).add();
        n.getVoltageLevel("VL1").newLoad().setId("LOAD1").setBus("B1").setConnectableBus("B1")
                .setP0(10).setQ0(0).add();
        newLine(n, "L01", 0, 1);
        newLine(n, "L12", 1, 2);
        return n;
    }

    private static void newLine(Network n, String id, int bus1, int bus2) {
        n.newLine().setId(id)
                .setVoltageLevel1("VL" + bus1).setBus1("B" + bus1).setConnectableBus1("B" + bus1)
                .setVoltageLevel2("VL" + bus2).setBus2("B" + bus2).setConnectableBus2("B" + bus2)
                .setR(0.1).setX(1).setG1(0).setB1(0).setG2(0).setB2(0).add();
    }

    private static Set<String> visitConnected(Bus bus) {
        Set<String> visited = new HashSet<>();
        bus.visitConnectedEquipments(new DefaultTopologyVisitor() {
            @Override
            public void visitGenerator(Generator generator) {
                visited.add(generator.getId());
            }

            @Override
            public void visitLine(Line line, TwoSides side) {
                visited.add(line.getId());
            }

            @Override
            public void visitLoad(Load load) {
                visited.add(load.getId());
            }
        });
        return visited;
    }

    @Test
    void addedEquipmentOnASharedBusShowsInBusViewAndBusBreakerViewReads() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "expansion", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("expansion");

        // add onto pre-existing shared buses: a generator on B1 and a shortcut line B0-B2
        n.getVoltageLevel("VL1").newGenerator().setId("XGEN").setBus("B1").setConnectableBus("B1")
                .setMinP(0).setMaxP(50).setTargetP(5).setTargetQ(0).setVoltageRegulatorOn(false).add();
        newLine(n, "XLINE", 0, 2);

        // bus view (merged bus): typed accessors, terminal enumeration, count, visitor
        Bus b1View = n.getBusView().getBus("VL1_0");
        assertEquals(Set.of("XGEN"), b1View.getGeneratorStream().map(Generator::getId).collect(Collectors.toSet()));
        assertEquals(4, b1View.getConnectedTerminalCount()); // L01, L12, LOAD1 + XGEN
        assertTrue(visitConnected(b1View).contains("XGEN"));

        Bus b0View = n.getBusView().getBus("VL0_0");
        assertEquals(Set.of("L01", "XLINE"), b0View.getLineStream().map(Line::getId).collect(Collectors.toSet()));
        assertTrue(visitConnected(b0View).contains("XLINE"));

        // bus-breaker view (the configured bus itself)
        Bus b1BbView = n.getBusBreakerView().getBus("B1");
        assertEquals(Set.of("XGEN"), b1BbView.getGeneratorStream().map(Generator::getId).collect(Collectors.toSet()));
        Bus b2BbView = n.getBusBreakerView().getBus("B2");
        assertEquals(Set.of("L12", "XLINE"), b2BbView.getLineStream().map(Line::getId).collect(Collectors.toSet()));

        // the connectable-or-connected visitor folds too
        Set<String> visited = new HashSet<>();
        b1BbView.visitConnectedOrConnectableEquipments(new DefaultTopologyVisitor() {
            @Override
            public void visitGenerator(Generator generator) {
                visited.add(generator.getId());
            }
        });
        assertTrue(visited.contains("XGEN"));

        // none of it leaks into the base variant
        vm.setWorkingVariant(INITIAL);
        assertEquals(0, n.getBusView().getBus("VL1_0").getGeneratorStream().count());
        assertEquals(3, n.getBusView().getBus("VL1_0").getConnectedTerminalCount());
        assertEquals(Set.of("L01"), n.getBusView().getBus("VL0_0").getLineStream().map(Line::getId).collect(Collectors.toSet()));
        assertFalse(visitConnected(n.getBusView().getBus("VL0_0")).contains("XLINE"));
        assertEquals(0, n.getBusBreakerView().getBus("B1").getGeneratorStream().count());
    }

    @Test
    void removedEquipmentDisappearsFromBusReadsInItsVariantOnly() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "n-1", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("n-1");

        n.getLoad("LOAD1").remove();
        n.getLine("L12").remove();

        Bus b1View = n.getBusView().getBus("VL1_0");
        assertEquals(0, b1View.getLoadStream().count());
        assertEquals(Set.of("L01"), b1View.getLineStream().map(Line::getId).collect(Collectors.toSet()));
        assertEquals(1, b1View.getConnectedTerminalCount());
        Set<String> visited = visitConnected(b1View);
        assertFalse(visited.contains("LOAD1"));
        assertFalse(visited.contains("L12"));

        vm.setWorkingVariant(INITIAL);
        assertEquals(1, n.getBusView().getBus("VL1_0").getLoadStream().count());
        assertEquals(3, n.getBusView().getBus("VL1_0").getConnectedTerminalCount());
        assertTrue(visitConnected(n.getBusView().getBus("VL1_0")).contains("L12"));
    }
}
