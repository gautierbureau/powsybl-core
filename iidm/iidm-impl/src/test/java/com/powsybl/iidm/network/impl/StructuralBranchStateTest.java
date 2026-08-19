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
}
