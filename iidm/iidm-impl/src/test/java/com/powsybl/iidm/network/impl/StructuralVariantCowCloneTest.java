/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.SwitchKind;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManager.VariantCloneStrategy;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end pinning of the O(1) {@code STRUCTURAL} clone through the public API: a structural clone
 * copies <b>no</b> bulk state in any columnar store, reads resolve to the source values, state diverges
 * per touched object, and the clone snapshot survives parent writes, variant removal, overwriting and
 * index recycling. This is the integration counterpart of the per-store
 * {@code *VariantStoreCowTest} classes.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class StructuralVariantCowCloneTest {

    private static final String INITIAL = VariantManagerConstants.INITIAL_VARIANT_ID;

    private static Network grid() {
        Network n = Network.create("cow-grid", "test");
        Substation s1 = n.newSubstation().setId("SUB1").add();
        VoltageLevel vl1 = s1.newVoltageLevel().setId("VL1").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl1.getBusBreakerView().newBus().setId("b1").add();
        vl1.newGenerator().setId("GEN1").setConnectableBus("b1").setBus("b1")
                .setMinP(0).setMaxP(500).setTargetP(200).setTargetQ(5).setTargetV(400).setVoltageRegulatorOn(true).add();
        vl1.newLoad().setId("LOAD1").setConnectableBus("b1").setBus("b1").setP0(90).setQ0(10).add();
        Substation s2 = n.newSubstation().setId("SUB2").add();
        VoltageLevel vl2 = s2.newVoltageLevel().setId("VL2").setNominalV(400).setTopologyKind(TopologyKind.NODE_BREAKER).add();
        vl2.getNodeBreakerView().newBusbarSection().setId("BBS").setNode(0).add();
        vl2.getNodeBreakerView().newSwitch().setId("SW1").setKind(SwitchKind.BREAKER).setNode1(0).setNode2(1).setOpen(false).add();
        n.newLine().setId("L").setVoltageLevel1("VL1").setConnectableBus1("b1").setBus1("b1")
                .setVoltageLevel2("VL2").setNode2(1)
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
        n.getGenerator("GEN1").getTerminal().setP(-200).setQ(-5);
        return n;
    }

    private static int variantIndex(Network n, String variantId) {
        VariantManagerImpl vm = ((NetworkImpl) n).getVariantManager();
        for (int i = 0; i < vm.getVariantArraySize(); i++) {
            if (variantId.equals(vm.getVariantId(i))) {
                return i;
            }
        }
        throw new AssertionError("variant not found: " + variantId);
    }

    @Test
    void aStructuralCloneCopiesNoBulkStateAndDivergesPerTouchedObject() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        NetworkImpl impl = (NetworkImpl) n;

        vm.cloneVariant(INITIAL, "fault", VariantCloneStrategy.STRUCTURAL);
        int fault = variantIndex(n, "fault");

        // O(1): no columnar store materialised anything for the structural variant
        assertEquals(0, impl.getTerminalVariantStore().cowRowsMaterialized(fault));
        assertEquals(0, impl.getSwitchVariantStore().cowRowsMaterialized(fault));

        // reads resolve to the source values through every store type
        vm.setWorkingVariant("fault");
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
        assertEquals(90.0, n.getLoad("LOAD1").getP0());
        assertEquals(-200.0, n.getGenerator("GEN1").getTerminal().getP());
        assertFalse(n.getSwitch("SW1").isOpen());

        // divergence is per touched object
        n.getGenerator("GEN1").setTargetP(120.0);
        n.getSwitch("SW1").setOpen(true);
        assertEquals(120.0, n.getGenerator("GEN1").getTargetP());
        assertTrue(n.getSwitch("SW1").isOpen());
        assertEquals(1, impl.getSwitchVariantStore().cowRowsMaterialized(fault));
        assertEquals(0, impl.getTerminalVariantStore().cowRowsMaterialized(fault)); // p/q untouched

        vm.setWorkingVariant(INITIAL);
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
        assertFalse(n.getSwitch("SW1").isOpen());
    }

    @Test
    void writingTheSourceAfterTheForkDoesNotLeakIntoTheFork() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "fault", VariantCloneStrategy.STRUCTURAL);

        // mutate the SOURCE variant after the fork: state and terminal values
        n.getGenerator("GEN1").setTargetP(333.0);
        n.getGenerator("GEN1").getTerminal().setP(-333.0);
        n.getSwitch("SW1").setOpen(true);

        vm.setWorkingVariant("fault");
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP()); // snapshot preserved
        assertEquals(-200.0, n.getGenerator("GEN1").getTerminal().getP());
        assertFalse(n.getSwitch("SW1").isOpen());

        vm.setWorkingVariant(INITIAL);
        assertEquals(333.0, n.getGenerator("GEN1").getTargetP());
    }

    @Test
    void removingTheMiddleVariantKeepsWhatItsForkInherited() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();

        vm.cloneVariant(INITIAL, "s1", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("s1");
        n.getGenerator("GEN1").setTargetP(150.0);

        vm.cloneVariant("s1", "s2", VariantCloneStrategy.STRUCTURAL);
        vm.removeVariant("s1");

        vm.setWorkingVariant("s2");
        assertEquals(150.0, n.getGenerator("GEN1").getTargetP()); // frozen from the removed parent
        assertEquals(90.0, n.getLoad("LOAD1").getP0());           // inherited-through value frozen too

        vm.setWorkingVariant(INITIAL);
        n.getLoad("LOAD1").setP0(75.0); // no longer an ancestor of s2's state
        vm.setWorkingVariant("s2");
        assertEquals(90.0, n.getLoad("LOAD1").getP0());
    }

    @Test
    void overwritingAndRecyclingStructuralVariants() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        NetworkImpl impl = (NetworkImpl) n;

        vm.cloneVariant(INITIAL, "s1", VariantCloneStrategy.STRUCTURAL);
        vm.setWorkingVariant("s1");
        n.getGenerator("GEN1").setTargetP(150.0);
        int s1 = variantIndex(n, "s1");

        // overwrite the structural variant with an eager clone of the initial variant: dense again
        vm.setWorkingVariant(INITIAL);
        vm.cloneVariant(INITIAL, List.of("s1"), VariantCloneStrategy.STATE_ONLY, true);
        vm.setWorkingVariant("s1");
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());

        // and the copy-on-write gate is released: no structural variant remains
        assertFalse(impl.getVariantManager().getCowState().isActive());

        // remove it and recycle its index as a fresh structural clone
        vm.setWorkingVariant(INITIAL);
        vm.removeVariant("s1");
        vm.cloneVariant(INITIAL, "s2", VariantCloneStrategy.STRUCTURAL);
        assertEquals(s1, variantIndex(n, "s2")); // the index was recycled
        vm.setWorkingVariant("s2");
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP()); // clean band: no leftover 150
        n.getGenerator("GEN1").setTargetP(110.0);
        vm.setWorkingVariant(INITIAL);
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
    }

    @Test
    void structuralContingencyVariantsStayO1UntilTouched() {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        NetworkImpl impl = (NetworkImpl) n;

        // the flagship workload: many contingency variants forked from the base, each removing a line
        List<String> variants = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = "c" + i;
            variants.add(id);
            vm.cloneVariant(INITIAL, id, VariantCloneStrategy.STRUCTURAL);
        }
        for (String id : variants) {
            int index = variantIndex(n, id);
            assertEquals(0, impl.getTerminalVariantStore().cowRowsMaterialized(index));
            assertEquals(0, impl.getSwitchVariantStore().cowRowsMaterialized(index));
        }

        vm.setWorkingVariant("c3");
        n.getLine("L").remove();
        assertNull(n.getLine("L"));
        vm.setWorkingVariant("c4");
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
        vm.setWorkingVariant(INITIAL);
        assertTrue(n.getLine("L") != null && n.getGenerator("GEN1").getTerminal().getP() == -200.0);
    }

    @Test
    void concurrentWorkersEachOnTheirOwnStructuralVariant() throws InterruptedException {
        Network n = grid();
        VariantManager vm = n.getVariantManager();
        int workers = 4;
        for (int i = 0; i < workers; i++) {
            vm.cloneVariant(INITIAL, "w" + i, VariantCloneStrategy.STRUCTURAL);
        }
        vm.allowVariantMultiThreadAccess(true);

        List<Thread> threads = new ArrayList<>();
        List<AssertionError> failures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            int w = i;
            threads.add(new Thread(() -> {
                vm.setWorkingVariant("w" + w);
                n.getGenerator("GEN1").setTargetP(100.0 + w);
                n.getGenerator("GEN1").getTerminal().setP(-(100.0 + w));
                for (int it = 0; it < 1000; it++) {
                    if (n.getGenerator("GEN1").getTargetP() != 100.0 + w
                            || n.getGenerator("GEN1").getTerminal().getP() != -(100.0 + w)) {
                        synchronized (failures) {
                            failures.add(new AssertionError("variant w" + w + " lost its values"));
                        }
                        return;
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(failures.isEmpty(), () -> failures.get(0).getMessage());

        vm.allowVariantMultiThreadAccess(false);
        vm.setWorkingVariant(INITIAL);
        assertEquals(200.0, n.getGenerator("GEN1").getTargetP());
    }
}
