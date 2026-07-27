/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gated copy-on-write semantics of {@link NumericVariantStore} (same model as
 * {@link TerminalVariantStoreCowTest}), covering the three primitive column types, the fill-across-variants
 * operations and the row free list.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class NumericVariantStoreCowTest {

    private static final double EPS = 0.0;

    private static NumericVariantStore newStore(VariantCowState cow) {
        // two double columns, one int column, one boolean column
        return new NumericVariantStore(1, new double[] {Double.NaN, Double.NaN}, new int[] {-1}, new boolean[] {false}, cow);
    }

    @Test
    void aStructuralCloneIsO1AndDivergesAllColumnTypesPerRow() {
        VariantCowState cow = new VariantCowState();
        NumericVariantStore store = newStore(cow);
        int r0 = store.allocateRow(new double[] {10.0, 1.0}, new int[] {7}, new boolean[] {true});
        int r1 = store.allocateRow(new double[] {20.0, 2.0}, new int[] {8}, new boolean[] {false});

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);

        assertEquals(0, store.cowRowsMaterialized(1));
        assertEquals(10.0, store.getDouble(1, 0, r0), EPS); // reads through
        assertEquals(7, store.getInt(1, 0, r0));
        assertTrue(store.getBoolean(1, 0, r0));

        store.setDouble(1, 0, r0, 99.0); // diverge r0: the whole row (all columns) materialises at once
        assertEquals(1, store.cowRowsMaterialized(1));
        assertEquals(99.0, store.getDouble(1, 0, r0), EPS);
        assertEquals(1.0, store.getDouble(1, 1, r0), EPS);  // other columns kept the parent values
        assertEquals(7, store.getInt(1, 0, r0));
        assertTrue(store.getBoolean(1, 0, r0));
        assertEquals(10.0, store.getDouble(0, 0, r0), EPS); // parent unchanged
        assertEquals(20.0, store.getDouble(1, 0, r1), EPS); // r1 still inherited

        store.setInt(1, 0, r1, 88);      // int and boolean writes materialise too
        store.setBoolean(1, 0, r1, true);
        assertEquals(88, store.getInt(1, 0, r1));
        assertTrue(store.getBoolean(1, 0, r1));
        assertEquals(8, store.getInt(0, 0, r1));
        assertFalse(store.getBoolean(0, 0, r1));
    }

    @Test
    void writingTheParentFreezesTheForkPerRow() {
        VariantCowState cow = new VariantCowState();
        NumericVariantStore store = newStore(cow);
        int r0 = store.allocateRow(new double[] {10.0, 1.0}, new int[] {7}, new boolean[] {false});

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);

        store.setInt(0, 0, r0, 42); // write the dense parent

        assertEquals(7, store.getInt(1, 0, r0)); // child kept its snapshot
        assertEquals(42, store.getInt(0, 0, r0));
        assertEquals(10.0, store.getDouble(1, 0, r0), EPS);
    }

    @Test
    void fillWritesEveryVariantIncludingDivergedRows() {
        VariantCowState cow = new VariantCowState();
        NumericVariantStore store = newStore(cow);
        int r0 = store.allocateRow(new double[] {10.0, 1.0}, new int[] {7}, new boolean[] {true});

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        store.setInt(1, 0, r0, 88); // diverge the row in the fork

        store.fillInt(0, r0, -5);       // fill = the same value in EVERY variant, diverged or not
        store.fillBoolean(0, r0, false);

        assertEquals(-5, store.getInt(0, 0, r0));
        assertEquals(-5, store.getInt(1, 0, r0));
        assertFalse(store.getBoolean(0, 0, r0));
        assertFalse(store.getBoolean(1, 0, r0));
        assertEquals(10.0, store.getDouble(1, 0, r0), EPS); // untouched columns keep their values
    }

    @Test
    void freedRowsClearTheirDivergenceOnReuse() {
        VariantCowState cow = new VariantCowState();
        NumericVariantStore store = newStore(cow);
        int r0 = store.allocateRow(new double[] {10.0, 1.0}, new int[] {7}, new boolean[] {false});

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        store.setDouble(1, 0, r0, 99.0);
        assertEquals(1, store.cowRowsMaterialized(1));

        store.freeRow(r0);
        int r1 = store.allocateRow(new double[] {30.0, 3.0}, new int[] {9}, new boolean[] {true});
        assertEquals(r0, r1);
        assertEquals(0, store.cowRowsMaterialized(1));       // stale divergence cleared
        assertEquals(30.0, store.getDouble(1, 0, r1), EPS);  // resolves to the fresh dense values
        assertEquals(9, store.getInt(1, 0, r1));
    }

    @Test
    void removalOverwriteAndRecyclingLifecycle() {
        VariantCowState cow = new VariantCowState();
        NumericVariantStore store = newStore(cow);
        int r0 = store.allocateRow(new double[] {10.0, 1.0}, new int[] {7}, new boolean[] {false});

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        store.setDouble(1, 0, r0, 99.0);
        cow.recordClone(2, 1, true);
        store.extendStructural(1, 1);

        // remove the middle variant: the grandchild keeps the values it inherited through it
        store.materializeInheritors(1);
        cow.forgetVariant(1);
        store.delete(1);
        assertEquals(99.0, store.getDouble(2, 0, r0), EPS);

        // recycle index 1 as an eager clone of the root: dense, sees the root's values
        cow.recordClone(1, 0, false);
        store.allocate(new int[] {1}, 0);
        assertEquals(10.0, store.getDouble(1, 0, r0), EPS);
        store.setDouble(1, 0, r0, 55.0);
        assertEquals(55.0, store.getDouble(1, 0, r0), EPS);
        assertEquals(10.0, store.getDouble(0, 0, r0), EPS);
        assertEquals(99.0, store.getDouble(2, 0, r0), EPS); // untouched by the recycling

        // a STATE_ONLY clone of the (still copy-on-write) grandchild copies its resolved band
        cow.recordClone(3, 2, false);
        store.extend(1, 2);
        assertEquals(99.0, store.getDouble(3, 0, r0), EPS);
        assertEquals(7, store.getInt(3, 0, r0));
    }

    @Test
    void strideGrowthPreservesDivergenceAcrossAllColumnTypes() {
        VariantCowState cow = new VariantCowState();
        NumericVariantStore store = newStore(cow);
        int r0 = store.allocateRow(new double[] {10.0, 1.0}, new int[] {7}, new boolean[] {true});
        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        store.setDouble(1, 0, r0, 99.0);
        store.setInt(1, 0, r0, 88);

        for (int i = 0; i < 40; i++) {
            store.allocateRow(new double[] {i, i}, new int[] {i}, new boolean[] {false});
        }

        assertEquals(99.0, store.getDouble(1, 0, r0), EPS);
        assertEquals(88, store.getInt(1, 0, r0));
        assertTrue(store.getBoolean(1, 0, r0));
        assertEquals(10.0, store.getDouble(0, 0, r0), EPS);
        assertEquals(39.0, store.getDouble(1, 0, 40), EPS); // new rows read through to the dense band
    }
}
