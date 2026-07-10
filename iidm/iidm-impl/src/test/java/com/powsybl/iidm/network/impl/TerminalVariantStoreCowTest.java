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
 * The live port of the {@code CowGatedColumnarStore} semantics onto {@link TerminalVariantStore}: the dense
 * fast path while no structural variant exists, an O(1) structural clone (zero rows stored) that reads
 * through to its parent, per-row divergence on write, and the snapshot frozen into copy-on-write children
 * when a parent is written, overwritten or removed.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class TerminalVariantStoreCowTest {

    private static final double EPS = 0.0;

    @Test
    void stateOnlyClonesStayOnTheDenseFastPath() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        store.setP(0, r0, 10.0);
        store.setQ(0, r0, 1.0);

        cow.recordClone(1, 0, false);
        store.extend(1, 0);
        assertFalse(cow.isActive());

        store.setP(1, r0, 55.0);
        assertEquals(55.0, store.getP(1, r0), EPS);
        assertEquals(10.0, store.getP(0, r0), EPS);
        assertEquals(1.0, store.getQ(1, r0), EPS); // eagerly copied
    }

    @Test
    void aStructuralCloneIsO1AndReadsThrough() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        int r1 = store.allocateRow();
        store.setP(0, r0, 10.0);
        store.setQ(0, r0, 1.0);
        store.setP(0, r1, 20.0);

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);

        assertTrue(cow.isActive());
        assertEquals(0, store.cowRowsMaterialized(1)); // O(1): nothing copied
        assertEquals(10.0, store.getP(1, r0), EPS);    // reads through to the dense parent
        assertEquals(20.0, store.getP(1, r1), EPS);

        // writing diverges only the touched row; the other column of the row is kept from the parent
        store.setP(1, r0, 99.0);
        assertEquals(99.0, store.getP(1, r0), EPS);
        assertEquals(1.0, store.getQ(1, r0), EPS);
        assertEquals(20.0, store.getP(1, r1), EPS);
        assertEquals(10.0, store.getP(0, r0), EPS);    // parent unchanged
        assertEquals(1, store.cowRowsMaterialized(1));
    }

    @Test
    void writingTheDenseParentAfterAStructuralForkPreservesTheChildSnapshot() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        store.setP(0, r0, 10.0);
        store.setQ(0, r0, 1.0);

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);

        store.setP(0, r0, 77.0); // mutate the DENSE parent after the structural fork

        assertEquals(10.0, store.getP(1, r0), EPS); // structural child kept its snapshot
        assertEquals(1.0, store.getQ(1, r0), EPS);
        assertEquals(77.0, store.getP(0, r0), EPS);
    }

    @Test
    void writingAStructuralParentFreezesItsStructuralChild() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        store.setP(0, r0, 10.0);

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        cow.recordClone(2, 1, true); // structural grandchild forked from the structural child
        store.extendStructural(1, 1);

        store.setP(1, r0, 50.0);                    // write the middle variant
        assertEquals(10.0, store.getP(2, r0), EPS); // grandchild frozen with the pre-write value
        assertEquals(50.0, store.getP(1, r0), EPS);
        assertEquals(10.0, store.getP(0, r0), EPS);

        store.setP(0, r0, 77.0);                    // writing the root does not affect either any more
        assertEquals(50.0, store.getP(1, r0), EPS);
        assertEquals(10.0, store.getP(2, r0), EPS);
    }

    @Test
    void aStateOnlyCloneOfAStructuralVariantCopiesItsResolvedBand() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        int r1 = store.allocateRow();
        store.setP(0, r0, 10.0);
        store.setP(0, r1, 20.0);

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        store.setP(1, r0, 99.0); // diverge one row only

        cow.recordClone(2, 1, false); // eager clone FROM the copy-on-write variant
        store.extend(1, 1);

        assertEquals(99.0, store.getP(2, r0), EPS); // diverged row from the structural source
        assertEquals(20.0, store.getP(2, r1), EPS); // inherited row resolved through the parentage

        store.setP(1, r1, 44.0); // later writes to the structural source do not leak into the eager copy
        assertEquals(20.0, store.getP(2, r1), EPS);
    }

    @Test
    void removingAVariantFreezesItsFullBandIntoItsStructuralChildren() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        int r1 = store.allocateRow();
        store.setP(0, r0, 10.0);
        store.setP(0, r1, 20.0);

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        store.setP(1, r0, 99.0);

        cow.recordClone(2, 1, true); // structural grandchild inherits r1 from variant 0 through variant 1
        store.extendStructural(1, 1);

        // remove variant 1: what the grandchild inherited through it must be frozen first
        store.materializeInheritors(1);
        cow.forgetVariant(1);
        store.delete(1);

        assertEquals(99.0, store.getP(2, r0), EPS); // kept variant 1's diverged value
        assertEquals(20.0, store.getP(2, r1), EPS); // kept the value resolved through variant 1

        store.setP(0, r1, 123.0); // the re-parented child no longer inherits anything
        assertEquals(20.0, store.getP(2, r1), EPS);
    }

    @Test
    void overwritingAStructuralVariantWithAnEagerCloneMakesItDenseAgain() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        store.setP(0, r0, 10.0);

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        store.setP(1, r0, 99.0);

        // overwrite variant 1 with a STATE_ONLY clone of the root, as the variant manager drives it
        store.materializeInheritors(1); // no children: no-op
        cow.recordClone(1, 0, false);
        store.allocate(new int[] {1}, 0);

        assertFalse(cow.isActive());                // last copy-on-write variant gone: fast path is back
        assertEquals(10.0, store.getP(1, r0), EPS); // the copy-on-write past was dropped
        store.setP(1, r0, 33.0);
        assertEquals(33.0, store.getP(1, r0), EPS);
        assertEquals(10.0, store.getP(0, r0), EPS);
    }

    @Test
    void recyclingAnIndexAsAStructuralVariantStartsFromACleanBand() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        store.setP(0, r0, 10.0);

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        store.setP(1, r0, 99.0);

        // overwrite the structural variant with a fresh structural clone of the root
        store.materializeInheritors(1);
        cow.recordClone(1, 0, true);
        store.allocateStructural(new int[] {1}, 0);

        assertEquals(0, store.cowRowsMaterialized(1));
        assertEquals(10.0, store.getP(1, r0), EPS); // inherits the root again, 99.0 is gone
    }

    @Test
    void rowsAllocatedAndRecycledWhileCopyOnWriteVariantsExist() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        store.setP(0, r0, 10.0);

        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);

        // a row allocated after the fork reads NaN everywhere, then diverges normally
        int r1 = store.allocateRow();
        assertTrue(Double.isNaN(store.getP(1, r1)));
        store.setP(0, r1, 20.0);
        assertEquals(20.0, store.getP(0, r1), EPS);
        assertTrue(Double.isNaN(store.getP(1, r1))); // frozen on the parent write

        store.setP(1, r1, 21.0);
        assertEquals(21.0, store.getP(1, r1), EPS);

        // recycling the row clears its divergence: the reused row reads NaN in every variant
        store.freeRow(r1);
        int r2 = store.allocateRow();
        assertEquals(r1, r2);
        assertTrue(Double.isNaN(store.getP(0, r2)));
        assertTrue(Double.isNaN(store.getP(1, r2)));
    }

    @Test
    void rowStrideGrowthKeepsDivergedRows() {
        VariantCowState cow = new VariantCowState();
        TerminalVariantStore store = new TerminalVariantStore(1, cow);
        int r0 = store.allocateRow();
        store.setP(0, r0, 10.0);
        cow.recordClone(1, 0, true);
        store.extendStructural(1, 0);
        store.setP(1, r0, 99.0);

        // grow past the default row capacity (16) to force a restride of flat and copy-on-write bands
        for (int i = 0; i < 40; i++) {
            int r = store.allocateRow();
            store.setP(0, r, 1000.0 + i);
        }

        assertEquals(99.0, store.getP(1, r0), EPS);
        assertEquals(10.0, store.getP(0, r0), EPS);
        assertEquals(1039.0, store.getP(0, 40), EPS);
        // each parent write froze the pre-write value (NaN, the row was new) into the earlier fork
        assertTrue(Double.isNaN(store.getP(1, 40)));
    }
}
