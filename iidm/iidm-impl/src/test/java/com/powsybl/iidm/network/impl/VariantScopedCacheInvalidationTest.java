/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Calculated buses are cached per variant. A variant-scoped structural edit must invalidate the cache of the
 * variant it was made in — and only that one — while an edit made in the initial variant, which every
 * variant that has not overridden it sees, must invalidate all of them.
 *
 * <p>The other bus-level tests read the bus view only after their edit, so they always hit a cold cache.
 * These read it before as well, which is what a real caller does: build the model, then apply a change.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantScopedCacheInvalidationTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("cache-invalidation", "test");
        for (int i = 0; i < 3; i++) {
            Substation s = n.newSubstation().setId("S" + i).add();
            VoltageLevel vl = s.newVoltageLevel().setId("VL" + i).setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl.getBusBreakerView().newBus().setId("B" + i).add();
        }
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

    /** Two islands: VL0-VL1 joined by a line, VL2 on its own. */
    private static Network islands() {
        Network n = Network.create("islands", "test");
        for (int i = 0; i < 3; i++) {
            Substation s = n.newSubstation().setId("S" + i).add();
            VoltageLevel vl = s.newVoltageLevel().setId("VL" + i).setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl.getBusBreakerView().newBus().setId("B" + i).add();
            vl.newLoad().setId("LD" + i).setBus("B" + i).setConnectableBus("B" + i).setP0(1).setQ0(0).add();
        }
        newLine(n, "L01", 0, 1);
        return n;
    }

    private static void addGenerator(Network n, String id) {
        n.getVoltageLevel("VL1").newGenerator().setId(id).setBus("B1").setConnectableBus("B1")
                .setMinP(0).setMaxP(50).setTargetP(5).setTargetQ(0).setVoltageRegulatorOn(false).add();
    }

    private static long componentCount(Network n) {
        return n.getBusView().getBusStream().map(b -> b.getConnectedComponent().getNum()).distinct().count();
    }

    private static int connectedTerminals(Network n) {
        return n.getBusView().getBus("VL1_0").getConnectedTerminalCount();
    }

    @Test
    void aScopedAddShowsThroughAnAlreadyComputedBusView() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "expansion");
        vm.setWorkingVariant("expansion");

        assertEquals(3, connectedTerminals(n)); // warms the cache: L01, L12, LOAD1
        addGenerator(n, "XGEN");

        assertEquals(4, connectedTerminals(n));
        assertEquals(Set.of("XGEN"), n.getBusView().getBus("VL1_0")
                .getGeneratorStream().map(Generator::getId).collect(Collectors.toSet()));
    }

    @Test
    void aScopedRemovalShowsThroughAnAlreadyComputedBusView() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "n-1");
        vm.setWorkingVariant("n-1");

        assertEquals(3, connectedTerminals(n)); // warms the cache
        n.getLoad("LOAD1").remove();

        assertEquals(2, connectedTerminals(n));
        assertEquals(0, n.getBusView().getBus("VL1_0").getLoadStream().count());
    }

    @Test
    void aScopedEditLeavesAnotherVariantsAlreadyComputedViewAlone() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "expansion");

        vm.setWorkingVariant(INITIAL);
        assertEquals(3, connectedTerminals(n)); // warm the base
        vm.setWorkingVariant("expansion");
        assertEquals(3, connectedTerminals(n)); // warm the variant

        addGenerator(n, "XGEN");
        assertEquals(4, connectedTerminals(n));

        vm.setWorkingVariant(INITIAL);
        assertEquals(3, connectedTerminals(n)); // untouched
        assertEquals(0, n.getBusView().getBus("VL1_0").getGeneratorStream().count());
    }

    /** The initial variant is the shared base, so its edits must reach a variant's warm cache too. */
    @Test
    void aBaseEditShowsThroughAVariantsAlreadyComputedBusView() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "wc");

        vm.setWorkingVariant("wc");
        assertEquals(3, connectedTerminals(n)); // warm the variant

        vm.setWorkingVariant(INITIAL);
        addGenerator(n, "BASEGEN");

        vm.setWorkingVariant("wc");
        assertEquals(4, connectedTerminals(n));
    }

    /**
     * Connected components are cached per variant too, and a scoped edit can change connectivity: here a
     * line added in a variant joins two islands that were computed as separate before the add.
     */
    @Test
    void aScopedAddShowsThroughAlreadyComputedConnectedComponents() {
        Network n = islands();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "expansion");
        vm.setWorkingVariant("expansion");

        assertEquals(2, componentCount(n)); // warm: VL0-VL1 on one island, VL2 alone

        newLine(n, "XLINE", 1, 2); // scoped: joins the two islands in this variant only

        assertEquals(1, componentCount(n));

        vm.setWorkingVariant(INITIAL);
        assertEquals(2, componentCount(n));
    }

    /** The mirror case: a scoped removal that splits a component. */
    @Test
    void aScopedRemovalShowsThroughAlreadyComputedConnectedComponents() {
        Network n = islands();
        newLine(n, "L12", 1, 2); // one component in the base: VL0-VL1-VL2
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "n-1");
        vm.setWorkingVariant("n-1");

        assertEquals(1, componentCount(n)); // warm

        n.getLine("L12").remove(); // scoped: splits VL2 off in this variant only

        assertEquals(2, componentCount(n));

        vm.setWorkingVariant(INITIAL);
        assertEquals(1, componentCount(n));
    }

    /** Node-breaker keeps a heavier per-variant cache (the calculated bus topology); same rule applies. */
    @Test
    void aScopedRemovalShowsThroughAnAlreadyComputedNodeBreakerBusView() {
        Network n = FourSubstationsNodeBreakerFactory.create();
        VariantManager vm = n.getVariantManager();
        vm.cloneVariant(INITIAL, "n-1");
        vm.setWorkingVariant("n-1");

        Bus bus = n.getVoltageLevel("S1VL2").getBusView().getBusStream().findFirst().orElseThrow();
        int before = bus.getConnectedTerminalCount(); // warms the calculated bus topology
        String loadId = bus.getLoadStream().findFirst().orElseThrow().getId();

        n.getLoad(loadId).remove();

        Bus after = n.getVoltageLevel("S1VL2").getBusView().getBusStream().findFirst().orElseThrow();
        assertEquals(before - 1, after.getConnectedTerminalCount());
        assertEquals(0, after.getLoadStream().filter(l -> loadId.equals(l.getId())).count());

        vm.setWorkingVariant(INITIAL);
        Bus base = n.getVoltageLevel("S1VL2").getBusView().getBusStream().findFirst().orElseThrow();
        assertEquals(before, base.getConnectedTerminalCount());
        assertEquals(1, base.getLoadStream().filter(l -> loadId.equals(l.getId())).count());
    }
}
