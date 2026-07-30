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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allocating a row writes the row once, not twice, so nothing initialises it to the column defaults before the
 * caller's values land on top. A recycled row therefore has to be fully overwritten by the allocation itself,
 * in every live variant band — otherwise a new object would surface the previous owner's values.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class NumericVariantStoreRowTest {

    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, 0d};
    private static final int[] INT_DEFAULTS = {-1};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int VARIANTS = 3;

    private NumericVariantStore store;

    @BeforeEach
    void setUp() {
        NetworkImpl network = (NetworkImpl) Network.create("test", "test");
        VariantManager variantManager = network.getVariantManager();
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v3");
        store = network.getOrCreateNumericVariantStore("key", DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
    }

    private void assertRowInEveryVariant(int row, double d0, double d1, int i0, boolean b0) {
        for (int v = 0; v < VARIANTS; v++) {
            assertEquals(d0, store.getDouble(v, 0, row), 0d, "double col 0, variant " + v);
            assertEquals(d1, store.getDouble(v, 1, row), 0d, "double col 1, variant " + v);
            assertEquals(i0, store.getInt(v, 0, row), "int col 0, variant " + v);
            assertEquals(b0, store.getBoolean(v, 0, row), "boolean col 0, variant " + v);
        }
    }

    @Test
    void allocateWithValuesFillsEveryVariantBand() {
        int row = store.allocateRow(new double[] {1d, 2d}, new int[] {7}, new boolean[] {true});
        assertRowInEveryVariant(row, 1d, 2d, 7, true);
    }

    @Test
    void allocateWithoutValuesFillsEveryVariantBandWithTheDefaults() {
        int row = store.allocateRow();
        for (int v = 0; v < VARIANTS; v++) {
            assertTrue(Double.isNaN(store.getDouble(v, 0, row)), "variant " + v);
            assertEquals(0d, store.getDouble(v, 1, row), 0d);
            assertEquals(-1, store.getInt(v, 0, row));
            assertFalse(store.getBoolean(v, 0, row));
        }
    }

    @Test
    void aRecycledRowKeepsNothingFromItsPreviousOwner() {
        int first = store.allocateRow(new double[] {1d, 2d}, new int[] {7}, new boolean[] {true});
        // diverge the variants too, so a partial reinitialisation would show up
        store.setDouble(1, 0, first, 99d);
        store.setInt(2, 0, first, 99);
        store.setBoolean(2, 0, first, true);
        store.freeRow(first);

        int reused = store.allocateRow(new double[] {5d, 6d}, new int[] {8}, new boolean[] {false});
        assertEquals(first, reused, "the freed row should have been recycled");
        assertRowInEveryVariant(reused, 5d, 6d, 8, false);
    }

    @Test
    void aRecycledRowResetsToTheDefaultsWhenAllocatedWithoutValues() {
        int first = store.allocateRow(new double[] {1d, 2d}, new int[] {7}, new boolean[] {true});
        store.setDouble(1, 0, first, 99d);
        store.freeRow(first);

        int reused = store.allocateRow();
        assertEquals(first, reused);
        for (int v = 0; v < VARIANTS; v++) {
            assertTrue(Double.isNaN(store.getDouble(v, 0, reused)), "variant " + v);
            assertEquals(0d, store.getDouble(v, 1, reused), 0d);
            assertEquals(-1, store.getInt(v, 0, reused));
            assertFalse(store.getBoolean(v, 0, reused));
        }
    }

    @Test
    void rowsAreRecycledInLastFreedFirstOrderAndAccountedFor() {
        int a = store.allocateRow();
        int b = store.allocateRow();
        assertEquals(2, store.getRowCount());
        assertEquals(0, store.getFreeRowCount());

        store.freeRow(a);
        store.freeRow(b);
        assertEquals(2, store.getFreeRowCount());

        assertEquals(b, store.allocateRow());
        assertEquals(a, store.allocateRow());
        assertEquals(0, store.getFreeRowCount());
        assertEquals(2, store.getRowCount(), "recycling must not hand out new rows");
    }

    @Test
    void growingTheRowStrideKeepsEveryRowInEveryVariant() {
        // DEFAULT_ROW_CAPACITY is 16, so this forces several stride doublings while three variants are live
        int[] rows = new int[100];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = store.allocateRow(new double[] {i, -i}, new int[] {i}, new boolean[] {i % 2 == 0});
        }
        for (int i = 0; i < rows.length; i++) {
            assertRowInEveryVariant(rows[i], i, -i, i, i % 2 == 0);
        }
    }
}
