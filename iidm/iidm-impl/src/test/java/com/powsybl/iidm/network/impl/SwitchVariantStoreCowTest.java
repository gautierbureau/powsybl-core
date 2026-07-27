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
 * Gated copy-on-write semantics of {@link SwitchVariantStore} (same model as
 * {@link TerminalVariantStoreCowTest}): dense fast path, O(1) structural clone reading through the
 * parentage, per-row divergence, snapshot frozen on parent write / overwrite / removal.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class SwitchVariantStoreCowTest {

    @Test
    void aCloneReadsThroughAndDivergesIndependently() {
        VariantCowState cow = new VariantCowState();
        SwitchVariantStore store = new SwitchVariantStore(1, cow);
        int r0 = store.allocateRow(false, true);

        cow.recordClone(1, 0);
        store.extend(1, 0);
        assertTrue(cow.isActive()); // every clone is copy-on-write

        store.setOpen(1, r0, true);
        assertTrue(store.getOpen(1, r0));
        assertFalse(store.getOpen(0, r0));
        assertTrue(store.getRetained(1, r0)); // untouched: still resolved through the parentage
    }

    @Test
    void aStructuralCloneIsO1AndReadsThrough() {
        VariantCowState cow = new VariantCowState();
        SwitchVariantStore store = new SwitchVariantStore(1, cow);
        int r0 = store.allocateRow(false, true);
        int r1 = store.allocateRow(true, false);

        cow.recordClone(1, 0);
        store.extend(1, 0);

        assertTrue(cow.isActive());
        assertEquals(0, store.cowRowsMaterialized(1));
        assertFalse(store.getOpen(1, r0));
        assertTrue(store.getRetained(1, r0));
        assertTrue(store.getOpen(1, r1));

        // a contingency opens one switch in the structural variant: only that row diverges
        store.setOpen(1, r0, true);
        assertTrue(store.getOpen(1, r0));
        assertTrue(store.getRetained(1, r0));  // other column of the row kept from the parent
        assertFalse(store.getOpen(0, r0));     // parent unchanged
        assertEquals(1, store.cowRowsMaterialized(1));
    }

    @Test
    void writingTheDenseParentAfterAStructuralForkPreservesTheChildSnapshot() {
        VariantCowState cow = new VariantCowState();
        SwitchVariantStore store = new SwitchVariantStore(1, cow);
        int r0 = store.allocateRow(false, false);

        cow.recordClone(1, 0);
        store.extend(1, 0);

        store.setOpen(0, r0, true); // mutate the DENSE parent after the structural fork

        assertFalse(store.getOpen(1, r0)); // structural child kept its snapshot
        assertTrue(store.getOpen(0, r0));
    }

    @Test
    void removalAndOverwriteFreezeAndCleanUp() {
        VariantCowState cow = new VariantCowState();
        SwitchVariantStore store = new SwitchVariantStore(1, cow);
        int r0 = store.allocateRow(false, false);
        int r1 = store.allocateRow(true, true);

        cow.recordClone(1, 0);
        store.extend(1, 0);
        store.setOpen(1, r0, true);

        cow.recordClone(2, 1);
        store.extend(1, 1);

        // remove the middle variant: the grandchild keeps everything it inherited through it
        store.materializeInheritors(1);
        cow.forgetVariant(1);
        store.delete(1);
        assertTrue(store.getOpen(2, r0));
        assertTrue(store.getOpen(2, r1));
        store.setOpen(0, r1, false);
        assertTrue(store.getOpen(2, r1)); // no longer inherits from the root

        // overwrite the grandchild with a fresh clone of the root: its own divergence is dropped
        store.materializeInheritors(2);
        cow.forgetVariant(2);
        cow.recordClone(2, 0);
        store.allocate(new int[] {2}, 0);
        assertTrue(cow.isActive());
        assertFalse(store.getOpen(2, r1));
        assertFalse(store.getOpen(2, r0));
    }

    @Test
    void rowsAllocatedAndRestridedWhileCopyOnWriteVariantsExist() {
        VariantCowState cow = new VariantCowState();
        SwitchVariantStore store = new SwitchVariantStore(1, cow);
        int r0 = store.allocateRow(true, false);
        cow.recordClone(1, 0);
        store.extend(1, 0);
        store.setRetained(1, r0, true);

        // new switches and stride growth while the fork exists
        for (int i = 0; i < 40; i++) {
            store.allocateRow(i % 2 == 0, false);
        }
        assertFalse(store.getOpen(1, 2));     // row 2 = loop i=1 (odd), inherited from the dense band
        assertTrue(store.getOpen(1, 3));      // row 3 = loop i=2 (even)
        assertTrue(store.getRetained(1, r0)); // diverged row survived the restride
        assertFalse(store.getRetained(0, r0));
        assertTrue(store.getOpen(0, r0));
        assertTrue(store.getOpen(1, r0));     // open column of the diverged row still the frozen parent value
    }
}
