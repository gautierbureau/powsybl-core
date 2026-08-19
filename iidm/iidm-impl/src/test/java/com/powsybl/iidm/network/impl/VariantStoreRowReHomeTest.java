/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.FictitiousSwitchFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-homing an object on merge/detach allocates it a row in the target network's store. The source row must be
 * released, otherwise the previous network keeps a dead row per moved object and every later variant clone goes
 * on copying them.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantStoreRowReHomeTest {

    private static Network mergedWithOther(Network toMerge) {
        Network other = Network.create("other", "test");
        other.newSubstation().setId("OTHER_S").add()
                .newVoltageLevel().setId("OTHER_VL").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                .getBusBreakerView().newBus().setId("OTHER_B").add();
        return Network.merge(toMerge, other);
    }

    @Test
    void detachReleasesTheTerminalRowsItLeavesBehind() {
        NetworkImpl merged = (NetworkImpl) mergedWithOther(EurostagTutorialExample1Factory.create());
        NumericVariantStore sourceStore = merged.getTerminalVariantStore();
        int rowsBefore = sourceStore.getRowCount();
        int freeBefore = sourceStore.getFreeRowCount();

        Network detached = merged.getSubnetwork("sim1").detach();

        // every terminal that moved must have handed its row back rather than abandoning it
        int moved = (int) detached.getConnectableStream().flatMap(c -> c.getTerminals().stream()).count();
        assertTrue(moved > 0);
        assertEquals(rowsBefore, sourceStore.getRowCount(), "no new rows should be handed out in the source");
        assertEquals(freeBefore + moved, sourceStore.getFreeRowCount(), "moved terminals must free their row");
    }

    @Test
    void detachReleasesTheSwitchRowsItLeavesBehind() {
        NetworkImpl merged = (NetworkImpl) mergedWithOther(FictitiousSwitchFactory.create());
        NumericVariantStore sourceStore = merged.getSwitchVariantStore();
        int freeBefore = sourceStore.getFreeRowCount();

        Network detached = merged.getSubnetwork("fictitious").detach();

        int moved = detached.getSwitchCount();
        assertTrue(moved > 0);
        assertEquals(freeBefore + moved, sourceStore.getFreeRowCount(), "moved switches must free their row");
    }

    @Test
    void freedRowsAreReusedByLaterEquipment() {
        NetworkImpl merged = (NetworkImpl) mergedWithOther(EurostagTutorialExample1Factory.create());
        NumericVariantStore sourceStore = merged.getTerminalVariantStore();
        merged.getSubnetwork("sim1").detach();

        int rowsBefore = sourceStore.getRowCount();
        assertTrue(sourceStore.getFreeRowCount() > 0);

        // adding equipment to what is left must consume a recycled row, not extend the store
        merged.getVoltageLevel("OTHER_VL").newLoad().setId("NEW").setBus("OTHER_B").setP0(1).setQ0(1).add();
        assertEquals(rowsBefore, sourceStore.getRowCount());
    }

    @Test
    void reHomedStateSurvivesAndStaysPerVariant() {
        Network eurostag = EurostagTutorialExample1Factory.create();
        eurostag.getGenerator("GEN").setTargetP(123d).setVoltageRegulatorOn(true);
        eurostag.getLoad("LOAD").getTerminal().setP(55d);
        eurostag.getTwoWindingsTransformer("NHV2_NLOAD").getRatioTapChanger().setTapPosition(0).setTargetDeadband(3d);

        Network detached = mergedWithOther(eurostag).getSubnetwork("sim1").detach();

        assertEquals(123d, detached.getGenerator("GEN").getTargetP(), 0d);
        assertEquals(55d, detached.getLoad("LOAD").getTerminal().getP(), 0d);
        assertTrue(detached.getGenerator("GEN").isVoltageRegulatorOn());
        assertEquals(0, detached.getTwoWindingsTransformer("NHV2_NLOAD").getRatioTapChanger().getTapPosition());
        assertEquals(3d, detached.getTwoWindingsTransformer("NHV2_NLOAD").getRatioTapChanger().getTargetDeadband(), 0d);

        VariantManager variantManager = detached.getVariantManager();
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        variantManager.setWorkingVariant("v2");
        assertEquals(123d, detached.getGenerator("GEN").getTargetP(), 0d);
        detached.getGenerator("GEN").setTargetP(300d);
        variantManager.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertEquals(123d, detached.getGenerator("GEN").getTargetP(), 0d);
    }
}
