/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.serde.test;

import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class Nordic32FactoryTest {

    @Test
    void readsTheNordic32Network() {
        Network network = Nordic32Factory.create();
        assertEquals("Nordic32_corrige", network.getId());
        // the Nordic 32 machines are there, named g01..g20
        assertNotNull(network.getGenerator("g01"));
        assertNotNull(network.getGenerator("g20"));
        assertTrue(network.getGeneratorCount() >= 20);
    }
}
