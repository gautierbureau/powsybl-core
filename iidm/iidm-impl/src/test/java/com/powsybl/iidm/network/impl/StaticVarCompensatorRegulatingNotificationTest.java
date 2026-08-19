/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.test.SvcTestCaseFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that changing whether a static var compensator regulates is notified to the network
 * listeners, the way its regulation mode and the regulating status of a tap changer are.
 */
class StaticVarCompensatorRegulatingNotificationTest {

    private record Update(String id, String attribute, String variantId, Object oldValue, Object newValue) { }

    @Test
    void setRegulatingIsNotified() {
        Network network = SvcTestCaseFactory.create();
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC2");
        assertTrue(svc.isRegulating());

        List<Update> updates = new ArrayList<>();
        network.addListener(new NetworkListener() {
            @Override
            public void onUpdate(Identifiable<?> identifiable, String attribute, String variantId, Object oldValue, Object newValue) {
                updates.add(new Update(identifiable.getId(), attribute, variantId, oldValue, newValue));
            }
        });

        svc.setRegulating(false);

        assertEquals(1, updates.size());
        Update update = updates.get(0);
        assertEquals("SVC2", update.id());
        assertEquals("regulating", update.attribute());
        // regulating depends on the variant, so the variant id has to be notified
        assertEquals(VariantManagerConstants.INITIAL_VARIANT_ID, update.variantId());
        assertEquals(Boolean.TRUE, update.oldValue());
        assertEquals(Boolean.FALSE, update.newValue());
    }

    @Test
    void setRegulatingIsNotNotifiedWhenUnchanged() {
        Network network = SvcTestCaseFactory.create();
        StaticVarCompensator svc = network.getStaticVarCompensator("SVC2");

        List<Update> updates = new ArrayList<>();
        network.addListener(new NetworkListener() {
            @Override
            public void onUpdate(Identifiable<?> identifiable, String attribute, String variantId, Object oldValue, Object newValue) {
                updates.add(new Update(identifiable.getId(), attribute, variantId, oldValue, newValue));
            }
        });

        svc.setRegulating(svc.isRegulating());

        assertTrue(updates.isEmpty());
    }
}
