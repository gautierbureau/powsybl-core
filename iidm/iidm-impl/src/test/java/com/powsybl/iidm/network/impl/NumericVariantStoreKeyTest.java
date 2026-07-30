/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The columnar stores are keyed by a string and addressed by column index, so two object types sharing a key
 * must agree on the column layout. These tests cover the guard that turns a colliding or mistyped key into an
 * immediate failure instead of silently handing one type another type's columns.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class NumericVariantStoreKeyTest {

    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, 0d};
    private static final int[] INT_DEFAULTS = {-1};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};

    private NetworkImpl network;

    @BeforeEach
    void setUp() {
        network = (NetworkImpl) Network.create("test", "test");
    }

    private NumericVariantStore get(double[] doubleDefaults, int[] intDefaults, boolean[] booleanDefaults) {
        return network.getOrCreateNumericVariantStore("key", doubleDefaults, intDefaults, booleanDefaults);
    }

    @Test
    void sameLayoutReturnsTheSameStore() {
        NumericVariantStore first = get(DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        // distinct array instances holding the same values, including NaN, must be accepted: the defaults are
        // per-class constants, so the second caller never passes the same arrays the store was created with
        NumericVariantStore second = get(new double[] {Double.NaN, 0d}, new int[] {-1}, new boolean[] {false});
        assertSame(first, second);
    }

    @Test
    void differentColumnCountIsRejected() {
        get(DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> get(new double[] {Double.NaN}, INT_DEFAULTS, BOOLEAN_DEFAULTS));
        assertTrue(e.getMessage().contains("'key'"), e.getMessage());
        assertTrue(e.getMessage().contains("different column layout"), e.getMessage());

        assertThrows(IllegalStateException.class, () -> get(DOUBLE_DEFAULTS, new int[] {-1, -1}, BOOLEAN_DEFAULTS));
        assertThrows(IllegalStateException.class, () -> get(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {}));
    }

    @Test
    void sameColumnCountButDifferentDefaultIsRejected() {
        get(DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);

        assertThrows(IllegalStateException.class, () -> get(new double[] {Double.NaN, 1d}, INT_DEFAULTS, BOOLEAN_DEFAULTS));
        assertThrows(IllegalStateException.class, () -> get(DOUBLE_DEFAULTS, new int[] {0}, BOOLEAN_DEFAULTS));
        assertThrows(IllegalStateException.class, () -> get(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {true}));
    }

    @Test
    void distinctKeysAreIndependent() {
        NumericVariantStore a = network.getOrCreateNumericVariantStore("a", DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        NumericVariantStore b = network.getOrCreateNumericVariantStore("b", new double[] {}, new int[] {}, new boolean[] {true});
        assertEquals(0, a.allocateRow());
        assertEquals(0, b.allocateRow());
        assertTrue(b.getBoolean(0, 0, 0));
    }
}
