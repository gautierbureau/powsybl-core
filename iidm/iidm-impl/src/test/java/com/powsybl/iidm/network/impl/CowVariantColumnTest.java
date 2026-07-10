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
 * Prototype for the deferred columnar copy-on-write clone ({@link CowVariantColumn}). See
 * {@code structural-variant-public-api.md} (the "O(1) clone" section).
 *
 * <p>Proves the mechanism that would take the current design (variant-scoped existence + membership, but
 * O(N) fork because {@code cloneVariant} copies every object's slot) to an O(1) clone: a variant field
 * stored copy-on-write over a parent-pointer forest. Forking is O(1) and copies nothing, yet IIDM's
 * snapshot semantics hold — a later change to a parent variant never leaks into a variant forked earlier.</p>
 *
 * @author Claude
 */
class CowVariantColumnTest {

    @Test
    void forkInheritsUntilTheChildDiverges() {
        CowVariantParentage p = new CowVariantParentage();
        int initial = p.createRoot();
        CowVariantColumn<String> col = new CowVariantColumn<>(p, "default");
        col.set(initial, "A");

        int faulted = p.fork(initial);
        assertEquals("A", col.get(faulted)); // inherited, nothing copied at fork

        col.set(faulted, "B");
        assertEquals("B", col.get(faulted)); // child diverged
        assertEquals("A", col.get(initial)); // parent unchanged
    }

    @Test
    void writingAParentAfterAForkDoesNotLeakIntoTheChild() {
        // The IIDM snapshot contract: cloneVariant is a point-in-time copy. Copy-on-write must preserve it.
        CowVariantParentage p = new CowVariantParentage();
        int initial = p.createRoot();
        CowVariantColumn<String> col = new CowVariantColumn<>(p, "default");
        col.set(initial, "A");
        int faulted = p.fork(initial);

        col.set(initial, "B"); // mutate the parent AFTER the fork

        assertEquals("A", col.get(faulted)); // child kept its snapshot, no leak
        assertEquals("B", col.get(initial));
    }

    @Test
    void forkIsConstantTimeAndCopiesNothing() {
        CowVariantParentage p = new CowVariantParentage();
        int initial = p.createRoot();
        CowVariantColumn<String> col = new CowVariantColumn<>(p, "default");
        col.set(initial, "A");
        assertEquals(1, col.storedEntries());

        int[] children = new int[1000];
        for (int i = 0; i < children.length; i++) {
            children[i] = p.fork(initial);
        }

        // forking 1000 variants copied no field state — storage is still just the one written entry
        assertEquals(1, col.storedEntries());
        assertEquals("A", col.get(children[0]));
        assertEquals("A", col.get(children[999]));
    }

    @Test
    void readsFallThroughMultipleLevels() {
        CowVariantParentage p = new CowVariantParentage();
        int initial = p.createRoot();
        CowVariantColumn<Double> col = new CowVariantColumn<>(p, Double.NaN);
        col.set(initial, 50.0);
        int faulted = p.fork(initial);
        int scenario = p.fork(faulted);

        assertEquals(50.0, col.get(scenario)); // through two levels of inheritance

        // diverging the middle variant freezes the already-forked descendant to its snapshot...
        col.set(faulted, 80.0);
        assertEquals(50.0, col.get(scenario)); // scenario forked before the write: keeps 50
        assertEquals(80.0, col.get(faulted));
        assertEquals(50.0, col.get(initial));

        // ...while a variant forked AFTER the write inherits the new value
        int scenario2 = p.fork(faulted);
        assertEquals(80.0, col.get(scenario2));
    }

    @Test
    void unwrittenColumnReturnsRootDefault() {
        CowVariantParentage p = new CowVariantParentage();
        int initial = p.createRoot();
        int faulted = p.fork(initial);
        CowVariantColumn<Boolean> exists = new CowVariantColumn<>(p, Boolean.TRUE);

        // nothing written anywhere: every variant sees the default (existence would default to "present")
        assertEquals(Boolean.TRUE, exists.get(initial));
        assertEquals(Boolean.TRUE, exists.get(faulted));

        // a variant-scoped tombstone is just a write in that variant
        exists.set(faulted, Boolean.FALSE);
        assertEquals(Boolean.FALSE, exists.get(faulted));
        assertEquals(Boolean.TRUE, exists.get(initial));
    }
}
