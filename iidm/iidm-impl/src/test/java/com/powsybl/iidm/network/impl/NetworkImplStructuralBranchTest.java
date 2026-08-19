/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural-variant spike, phase 2: a structural branch created with
 * {@link NetworkImpl#createStructuralBranch} is a fully traversable {@code Network} that shares its
 * base by reference. This exercises the branch through the <em>public</em> {@code Network} API (not
 * the index directly): reads fall through to the shared base, structural additions are isolated to
 * the branch, and the base is never mutated.
 *
 * <p>Running the existing {@code ConnectVoltageLevelOnLine} modification unchanged on a branch —
 * which needs copy-on-write of the two endpoint voltage levels on the write path — is phase 2b (see
 * {@code structural-variant-spike.md}).</p>
 *
 * @author Claude
 */
class NetworkImplStructuralBranchTest {

    @Test
    void branchMirrorsBaseThroughPublicApi() {
        NetworkImpl base = (NetworkImpl) EurostagTutorialExample1Factory.create();
        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, "branch");

        // Reads fall through to the shared base — same counts, same instances.
        assertEquals(base.getVoltageLevelCount(), branch.getVoltageLevelCount());
        assertEquals(base.getLineCount(), branch.getLineCount());
        assertEquals(base.getSubstationCount(), branch.getSubstationCount());
        assertSame(base.getLine("NHV1_NHV2_1"), branch.getLine("NHV1_NHV2_1"));
        assertNotNull(branch.getVoltageLevel("VLHV1"));
        assertEquals(base.getVoltageLevelStream().count(), branch.getVoltageLevelStream().count());
        assertEquals(base.getIdentifiable("VLGEN"), branch.getIdentifiable("VLGEN"));
    }

    @Test
    void structuralAdditionToBranchIsIsolatedFromBase() {
        NetworkImpl base = (NetworkImpl) EurostagTutorialExample1Factory.create();
        int baseVlCount = base.getVoltageLevelCount();
        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, "branch");

        // Create a fictitious mid-line voltage level in the BRANCH, through the public API.
        Substation s = branch.newSubstation().setId("FICT_S").setFictitious(true).add();
        VoltageLevel fvl = s.newVoltageLevel().setId("FICT_VL").setNominalV(380.0).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        fvl.getBusBreakerView().newBus().setId("FICT_BUS").add();

        // The branch reflects the addition.
        assertEquals(baseVlCount + 1, branch.getVoltageLevelCount());
        assertNotNull(branch.getVoltageLevel("FICT_VL"));
        assertNotNull(branch.getSubstation("FICT_S"));

        // The shared base is untouched.
        assertEquals(baseVlCount, base.getVoltageLevelCount());
        assertNull(base.getVoltageLevel("FICT_VL"));
        assertNull(base.getSubstation("FICT_S"));
    }

    @Test
    void tombstonedBaseObjectDisappearsFromBranchButNotBase() {
        NetworkImpl base = (NetworkImpl) EurostagTutorialExample1Factory.create();
        int baseLineCount = base.getLineCount();
        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, "branch");

        // Hide the line to be split from the branch (public-API-driven removal is phase 2b).
        Line line = base.getLine("NHV1_NHV2_1");
        ((OverlayNetworkIndex) branch.getIndex()).tombstone(line);

        // Reflected through the public Network API of the branch...
        assertNull(branch.getLine("NHV1_NHV2_1"));
        assertEquals(baseLineCount - 1, branch.getLineCount());
        // ...while the base still has it.
        assertNotNull(base.getLine("NHV1_NHV2_1"));
        assertEquals(baseLineCount, base.getLineCount());
    }

    @Test
    void copyOnWriteReplaceShadowsBaseWithBranchCopy() {
        // The write-path primitive: materialise a base object into the branch as a same-id copy, so it
        // can be mutated in the branch without touching the shared base.
        NetworkImpl base = (NetworkImpl) EurostagTutorialExample1Factory.create();
        int baseVlCount = base.getVoltageLevelCount();
        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, "branch");

        VoltageLevel baseVl = base.getVoltageLevel("VLHV1");
        // a branch-local copy carrying the same id (minted here in a donor network)
        VoltageLevel branchCopy = ((NetworkImpl) EurostagTutorialExample1Factory.create()).getVoltageLevel("VLHV1");
        assertNotSame(baseVl, branchCopy);

        ((OverlayNetworkIndex) branch.getIndex()).replace(baseVl, branchCopy);

        // the branch resolves the id to its own copy; the base still resolves to the original
        assertSame(branchCopy, branch.getVoltageLevel("VLHV1"));
        assertSame(baseVl, base.getVoltageLevel("VLHV1"));
        // a replacement changes which instance answers, not the counts
        assertEquals(baseVlCount, branch.getVoltageLevelCount());
        assertEquals(baseVlCount, base.getVoltageLevelCount());
    }

    @Test
    void baseAndBranchAreDistinctNetworks() {
        NetworkImpl base = (NetworkImpl) EurostagTutorialExample1Factory.create();
        NetworkImpl branch = NetworkImpl.createStructuralBranch(base, "branch");
        assertEquals("branch", branch.getId());
        assertTrue(branch.getIndex() instanceof OverlayNetworkIndex);
    }
}
