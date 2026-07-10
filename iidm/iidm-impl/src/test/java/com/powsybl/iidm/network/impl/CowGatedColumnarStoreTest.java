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
 * Prototype for the deferred columnar copy-on-write clone — the gated variant. See
 * {@code structural-variant-public-api.md} (the "O(1) clone" section). Proves the gating: no cost for
 * networks without a structural
 * variant (the dense fast path), O(1) structural clone even from a dense parent, correct reads across the
 * dense→sparse boundary, and snapshot preserved when a dense parent is written after a structural fork.
 *
 * @author Claude
 */
class CowGatedColumnarStoreTest {

    private static final int COLS = 2;

    @Test
    void withoutAStructuralVariantEverythingStaysOnTheDenseFastPath() {
        CowVariantParentage p = new CowVariantParentage();
        int root = p.createRoot();
        CowGatedColumnarStore store = new CowGatedColumnarStore(p, COLS, root);
        int r0 = store.allocateRow(10.0, 1.0);

        // ordinary STATE_ONLY clones keep the store on the dense fast path — no copy-on-write machinery
        int stateOnly = p.fork(root);
        store.cloneStateOnly(stateOnly, root);
        assertTrue(store.isFastPath());

        store.set(stateOnly, r0, 0, 55.0);
        assertEquals(55.0, store.get(stateOnly, r0, 0)); // state-only diverged (eager copy)
        assertEquals(10.0, store.get(root, r0, 0));       // root unchanged
        assertTrue(store.isFastPath());                   // still dense throughout
    }

    @Test
    void aStructuralCloneFromADenseParentIsO1AndReadsThrough() {
        CowVariantParentage p = new CowVariantParentage();
        int root = p.createRoot();
        CowGatedColumnarStore store = new CowGatedColumnarStore(p, COLS, root);
        int r0 = store.allocateRow(10.0, 1.0);
        int r1 = store.allocateRow(20.0, 2.0);

        int fault = p.fork(root);
        store.cloneStructural(fault, root);

        // O(1): the structural variant copied nothing (dense root has 2 rows; the fork has 0)...
        assertFalse(store.isFastPath());  // copy-on-write is now active
        assertEquals(2, store.rowsStored(root));
        assertEquals(0, store.rowsStored(fault));
        // ...yet it reads through to the dense parent
        assertEquals(10.0, store.get(fault, r0, 0));
        assertEquals(2.0, store.get(fault, r1, 1));

        // writing the structural variant diverges only the touched row
        store.set(fault, r0, 0, 99.0);
        assertEquals(99.0, store.get(fault, r0, 0));
        assertEquals(1.0, store.get(fault, r0, 1)); // other column of r0 kept from the dense parent
        assertEquals(20.0, store.get(fault, r1, 0)); // r1 still inherited
        assertEquals(10.0, store.get(root, r0, 0));   // dense parent unchanged
        assertEquals(1, store.rowsStored(fault));     // only r0 materialised
    }

    @Test
    void writingADenseParentAfterAStructuralForkPreservesTheChildSnapshot() {
        // The hard boundary case: the parent is dense, the child is copy-on-write. The dense write must
        // still freeze the row into the copy-on-write child.
        CowVariantParentage p = new CowVariantParentage();
        int root = p.createRoot();
        CowGatedColumnarStore store = new CowGatedColumnarStore(p, COLS, root);
        int r0 = store.allocateRow(10.0, 1.0);
        int fault = p.fork(root);
        store.cloneStructural(fault, root);

        store.set(root, r0, 0, 77.0); // mutate the DENSE parent after the structural fork

        assertEquals(10.0, store.get(fault, r0, 0)); // structural child kept its snapshot
        assertEquals(77.0, store.get(root, r0, 0));  // dense parent updated
    }
}
