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
import com.powsybl.iidm.network.Switch;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural-variant spike: a structural branch also carries its own <em>operating point</em> (variant
 * state) over the shared structure. The branch's {@link BranchContext} folds in a base variant; while
 * the context is active, the base's working variant is switched to it, so a <em>shared</em> object's
 * per-variant state (here a switch's open/closed) is read and written in the branch's column — without
 * materialising the object — and the base's own operating point is untouched.
 *
 * @author Claude
 */
class StructuralBranchStateTest {

    @Test
    void branchCarriesItsOwnOperatingPointForSharedObjects() {
        // base: two buses joined by a switch, initially closed
        Network base = Network.create("base", "test");
        Substation s = base.newSubstation().setId("S").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VL").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("b1").add();
        vl.getBusBreakerView().newBus().setId("b2").add();
        vl.getBusBreakerView().newSwitch().setId("SW").setBus1("b1").setBus2("b2").setOpen(false).add();

        NetworkImpl baseImpl = (NetworkImpl) base;
        NetworkImpl branch = NetworkImpl.createStructuralBranch(baseImpl, "branch");
        BranchContext ctx = branch.getBranchContext();

        // fold a per-branch operating point (a base variant) into the context
        base.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "branch-op");
        ctx.setStateVariant("branch-op");

        Switch sw = base.getSwitch("SW");
        assertFalse(sw.isOpen(), "base default variant: switch closed");

        // open the switch in the branch's operating point only
        ThreadLocalBranchContext.run(ctx, () -> sw.setOpen(true));

        // the base's own operating point is untouched...
        assertFalse(sw.isOpen(), "base default variant still closed");
        assertEquals(VariantManagerConstants.INITIAL_VARIANT_ID, base.getVariantManager().getWorkingVariantId(),
                "base working variant restored after the branch context");
        // ...while the branch sees its own state
        ThreadLocalBranchContext.run(ctx, () -> assertTrue(sw.isOpen(), "branch operating point: switch open"));
    }

    @Test
    void unifiedOperatingPointCoversSharedAndBranchOwnedObjects() {
        // base: a shared switch, closed
        Network base = Network.create("base", "test");
        Substation s = base.newSubstation().setId("S").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VL").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("b1").add();
        vl.getBusBreakerView().newBus().setId("b2").add();
        vl.getBusBreakerView().newSwitch().setId("S_shared").setBus1("b1").setBus2("b2").setOpen(false).add();

        NetworkImpl branch = NetworkImpl.createStructuralBranch((NetworkImpl) base, "branch");
        // a branch-owned switch, closed
        Substation sb = branch.newSubstation().setId("SB").add();
        VoltageLevel vlb = sb.newVoltageLevel().setId("VLB").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlb.getBusBreakerView().newBus().setId("c1").add();
        vlb.getBusBreakerView().newBus().setId("c2").add();
        vlb.getBusBreakerView().newSwitch().setId("S_branch").setBus1("c1").setBus2("c2").setOpen(false).add();

        BranchContext ctx = branch.getBranchContext();
        ctx.allocateOperatingPoint("op");

        Switch shared = base.getSwitch("S_shared");
        Switch owned = branch.getSwitch("S_branch");

        // open both in the branch's operating point
        ThreadLocalBranchContext.run(ctx, () -> {
            shared.setOpen(true);
            owned.setOpen(true);
        });

        // the operating point holds both; the defaults hold neither
        ThreadLocalBranchContext.run(ctx, () -> {
            assertTrue(shared.isOpen(), "shared object open in the operating point");
            assertTrue(owned.isOpen(), "branch-owned object open in the operating point");
        });
        assertFalse(shared.isOpen(), "shared object closed in the base default variant");
        assertFalse(owned.isOpen(), "branch-owned object closed in the branch default variant");
    }

    @Test
    void concurrentBranchesHaveIsolatedOperatingPoints() throws InterruptedException {
        // base with a shared switch; two branches each with their own operating point, run on two threads
        Network base = Network.create("base", "test");
        Substation s = base.newSubstation().setId("S").add();
        VoltageLevel vl = s.newVoltageLevel().setId("VL").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vl.getBusBreakerView().newBus().setId("b1").add();
        vl.getBusBreakerView().newBus().setId("b2").add();
        vl.getBusBreakerView().newSwitch().setId("SW").setBus1("b1").setBus2("b2").setOpen(false).add();

        NetworkImpl b1 = NetworkImpl.createStructuralBranch((NetworkImpl) base, "branch1");
        NetworkImpl b2 = NetworkImpl.createStructuralBranch((NetworkImpl) base, "branch2");
        b1.getBranchContext().allocateOperatingPoint("op1");
        b2.getBranchContext().allocateOperatingPoint("op2");

        Switch sw = base.getSwitch("SW");
        boolean[] seenByT1 = new boolean[1];
        boolean[] seenByT2 = new boolean[1];
        Thread t1 = new Thread(() -> ThreadLocalBranchContext.run(b1.getBranchContext(), () -> {
            sw.setOpen(true);
            seenByT1[0] = sw.isOpen();
        }));
        Thread t2 = new Thread(() -> ThreadLocalBranchContext.run(b2.getBranchContext(), () -> {
            sw.setOpen(false);
            seenByT2[0] = sw.isOpen();
        }));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // each thread saw its own operating point despite mutating the same shared switch concurrently
        assertTrue(seenByT1[0], "op1 (thread 1): switch open");
        assertFalse(seenByT2[0], "op2 (thread 2): switch closed");
        // the base default variant is untouched by either branch
        assertFalse(sw.isOpen(), "base default variant: switch still closed");
    }
}
