/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.extensions.ControlUnit;
import com.powsybl.iidm.network.extensions.ControlZone;
import com.powsybl.iidm.network.extensions.PilotPoint;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControl;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControlAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the columnar variant storage of network-level multi-variant extensions across a detach.
 *
 * <p>{@link SecondaryVoltageControl} keeps its per-variant state (pilot-point target voltage, control-unit
 * participation) in the network-level columnar stores. Unlike regular identifiables, it lives on the Network
 * object rather than in the identifiables index, so the detach re-home cascade must reach it explicitly.
 * Before the fix, {@code detach()} re-homed only the indexed identifiables, leaving the extension's store
 * reference pointing at the former owner's store; a subsequent variant clone on the detached network then
 * failed to extend that store, corrupting reads (or throwing {@link ArrayIndexOutOfBoundsException}).</p>
 *
 * @author Claude Code
 */
class DetachSecondaryVoltageControlVariantTest {

    @Test
    void variantCloneAfterDetachKeepsSecondaryVoltageControlState() {
        // A network carrying a SecondaryVoltageControl (a network-level, multi-variant extension).
        Network withSvc = EurostagTutorialExample1Factory.createWithMoreGenerators();
        withSvc.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                    .withName("z1")
                    .newPilotPoint()
                        .withBusbarSectionsOrBusesIds(List.of("NLOAD"))
                        .withTargetV(15d)
                    .add()
                    .newControlUnit()
                        .withId("GEN")
                        .withParticipate(false)
                    .add()
                    .newControlUnit()
                        .withId("GEN2")
                        .withParticipate(true)
                    .add()
                .add()
            .add();

        // Merge with a second, independent network so the SVC-carrying network becomes a detachable subnetwork.
        Network other = Network.create("other", "test");
        other.newSubstation().setId("OTHER_S").add()
                .newVoltageLevel().setId("OTHER_VL").setNominalV(400).setTopologyKind(com.powsybl.iidm.network.TopologyKind.BUS_BREAKER).add()
                .getBusBreakerView().newBus().setId("OTHER_B").add();

        Network merged = Network.merge(withSvc, other);

        // Detach the SVC-carrying subnetwork. This must re-home the extension's columnar variant state into
        // the detached network's stores (the bug: it did not).
        Network detached = merged.getSubnetwork("sim1").detach();

        SecondaryVoltageControl svc = detached.getExtension(SecondaryVoltageControl.class);
        assertNotNull(svc, "SVC extension should survive detach");
        ControlZone zone = svc.getControlZones().get(0);
        PilotPoint pilotPoint = zone.getPilotPoint();
        ControlUnit gen = zone.getControlUnit("GEN").orElseThrow();
        ControlUnit gen2 = zone.getControlUnit("GEN2").orElseThrow();

        // Baseline state on the initial variant is intact after detach.
        assertEquals(15d, pilotPoint.getTargetV(), 0d);
        assertFalse(gen.isParticipate());
        assertTrue(gen2.isParticipate());

        // Cloning a variant on the detached network must extend the re-homed columnar store; before the fix
        // this either threw ArrayIndexOutOfBoundsException or read stale values from the former owner's store.
        VariantManager variantManager = detached.getVariantManager();
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        variantManager.setWorkingVariant("v2");

        // The clone inherits the source variant's state.
        assertEquals(15d, pilotPoint.getTargetV(), 0d);
        assertFalse(gen.isParticipate());
        assertTrue(gen2.isParticipate());

        // Divergence in the new variant must be isolated from the initial one.
        pilotPoint.setTargetV(16d);
        gen.setParticipate(true);
        assertEquals(16d, pilotPoint.getTargetV(), 0d);
        assertTrue(gen.isParticipate());

        variantManager.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertEquals(15d, pilotPoint.getTargetV(), 0d);
        assertFalse(gen.isParticipate());
    }
}
