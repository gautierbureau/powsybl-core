/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A columnar owner created after variants already exist gets its row initialised in every live band, not just
 * the working one — whether or not its store already existed. An extension is the interesting case, because it
 * is the one owner routinely added long after the network was built, and it is what creates its store lazily.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class LateColumnarOwnerTest {

    @Test
    void anExtensionAddedAfterCloningIsCorrectInEveryVariant() {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager variantManager = network.getVariantManager();
        for (int i = 1; i <= 4; i++) {
            variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v" + i);
        }

        // first ActivePowerControl in the network: creates the store lazily, with 5 variants already live
        Generator gen = network.getGenerator("GEN");
        gen.newExtension(ActivePowerControlAdder.class)
                .withParticipate(true).withDroop(4f).withParticipationFactor(0.5).add();
        ActivePowerControl<Generator> apc = gen.getExtension(ActivePowerControl.class);

        for (String v : variantManager.getVariantIds()) {
            variantManager.setWorkingVariant(v);
            assertTrue(apc.isParticipate(), "participate in " + v);
            assertEquals(4f, apc.getDroop(), 0f, "droop in " + v);
            assertEquals(0.5, apc.getParticipationFactor(), 0d, "participation factor in " + v);
        }

        // and the variants stay independent afterwards
        variantManager.setWorkingVariant("v2");
        apc.setParticipate(false);
        variantManager.setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertTrue(apc.isParticipate());
    }

    @Test
    void aSecondOwnerOfAnExistingStoreAddedAfterCloningIsAlsoCorrect() {
        Network network = EurostagTutorialExample1Factory.createWithMoreGenerators();
        Generator first = network.getGenerator("GEN");
        first.newExtension(ActivePowerControlAdder.class)
                .withParticipate(true).withDroop(1f).withParticipationFactor(0.1).add();

        VariantManager variantManager = network.getVariantManager();
        for (int i = 1; i <= 4; i++) {
            variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v" + i);
        }

        // store already exists and has been extended; a new row must still be filled in every band
        Generator second = network.getGenerator("GEN2");
        second.newExtension(ActivePowerControlAdder.class)
                .withParticipate(false).withDroop(9f).withParticipationFactor(0.9).add();
        ActivePowerControl<Generator> apc = second.getExtension(ActivePowerControl.class);

        for (String v : variantManager.getVariantIds()) {
            variantManager.setWorkingVariant(v);
            assertEquals(9f, apc.getDroop(), 0f, "droop in " + v);
            assertEquals(0.9, apc.getParticipationFactor(), 0d, "participation factor in " + v);
        }
    }
}
