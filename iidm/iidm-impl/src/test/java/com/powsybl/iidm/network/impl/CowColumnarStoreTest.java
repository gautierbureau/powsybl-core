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

/**
 * Prototype for the deferred columnar copy-on-write clone. See {@code structural-variant-public-api.md}
 * (the "O(1) clone" section). Proves the mechanism that would make a {@code STRUCTURAL} clone O(1) for the
 * bulk per-variant state: a columnar store made copy-on-write over the variant tree, with per-row
 * divergence and snapshot-correct freeze-on-parent-write.
 *
 * @author Claude
 */
class CowColumnarStoreTest {

    private static final int COLS = 2; // e.g. p and q

    @Test
    void forkIsConstantTimeAndCopiesNothing() {
        CowVariantParentage p = new CowVariantParentage();
        int root = p.createRoot();
        CowColumnarStore store = new CowColumnarStore(p, COLS);
        int r0 = store.allocateRow(root, 10.0, 1.0);
        int r1 = store.allocateRow(root, 20.0, 2.0);
        int r2 = store.allocateRow(root, 30.0, 3.0);
        assertEquals(3, store.materialisedRowCount());

        // fork 100 variants: nothing is copied, storage stays at the three root rows
        int[] children = new int[100];
        for (int i = 0; i < children.length; i++) {
            children[i] = p.fork(root);
        }
        assertEquals(3, store.materialisedRowCount());

        // every fork inherits the root's values
        assertEquals(10.0, store.get(children[0], r0, 0));
        assertEquals(3.0, store.get(children[99], r2, 1));
        assertEquals(20.0, store.get(children[50], r1, 0));
    }

    @Test
    void aWriteDivergesOneRowInOneVariantOnly() {
        CowVariantParentage p = new CowVariantParentage();
        int root = p.createRoot();
        CowColumnarStore store = new CowColumnarStore(p, COLS);
        int r0 = store.allocateRow(root, 10.0, 1.0);
        int r1 = store.allocateRow(root, 20.0, 2.0);
        int child = p.fork(root);

        store.set(child, r0, 0, 99.0);

        // only child's r0 diverged: 2 root rows + 1 child row
        assertEquals(3, store.materialisedRowCount());
        assertEquals(99.0, store.get(child, r0, 0));   // child diverged
        assertEquals(1.0, store.get(child, r0, 1));    // other column of r0 kept from root
        assertEquals(20.0, store.get(child, r1, 0));   // r1 still inherited (per-row, not per-band)
        assertEquals(10.0, store.get(root, r0, 0));     // root unchanged
    }

    @Test
    void writingAParentAfterAForkPreservesTheChildSnapshot() {
        // The IIDM snapshot contract: a change to a parent variant must not leak into a variant forked
        // earlier. Copy-on-write preserves it by freezing the row into inheriting children on the write.
        CowVariantParentage p = new CowVariantParentage();
        int root = p.createRoot();
        CowColumnarStore store = new CowColumnarStore(p, COLS);
        int r0 = store.allocateRow(root, 10.0, 1.0);
        int child = p.fork(root);

        store.set(root, r0, 0, 77.0); // mutate the parent AFTER the fork

        assertEquals(10.0, store.get(child, r0, 0)); // child kept its snapshot
        assertEquals(77.0, store.get(root, r0, 0));  // parent updated
    }

    @Test
    void readsFallThroughLevelsAndFreezeIsPerLevel() {
        CowVariantParentage p = new CowVariantParentage();
        int root = p.createRoot();
        CowColumnarStore store = new CowColumnarStore(p, COLS);
        int r0 = store.allocateRow(root, 5.0, 0.0);
        int a = p.fork(root);
        int b = p.fork(a);          // b inherits through a, through root

        assertEquals(5.0, store.get(b, r0, 0)); // two levels of fall-through

        // diverging the middle variant freezes the already-forked descendant to its snapshot...
        store.set(a, r0, 0, 8.0);
        assertEquals(5.0, store.get(b, r0, 0)); // b forked before the write: keeps 5
        assertEquals(8.0, store.get(a, r0, 0));
        assertEquals(5.0, store.get(root, r0, 0));

        // ...while a variant forked AFTER the write inherits the new value
        int c = p.fork(a);
        assertEquals(8.0, store.get(c, r0, 0));
    }

    @Test
    void writingSeveralRowsMaterialisesOnlyThoseRows() {
        CowVariantParentage p = new CowVariantParentage();
        int root = p.createRoot();
        CowColumnarStore store = new CowColumnarStore(p, COLS);
        int r0 = store.allocateRow(root, 1.0, 0.0);
        int r1 = store.allocateRow(root, 2.0, 0.0);
        store.allocateRow(root, 3.0, 0.0); // r2, never touched
        int child = p.fork(root);

        store.set(child, r0, 0, 100.0);
        store.set(child, r1, 0, 200.0);

        // 3 root rows + 2 diverged child rows = 5 (r2 never materialised in the child)
        assertEquals(5, store.materialisedRowCount());
    }
}
