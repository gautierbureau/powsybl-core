/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.Arrays;

/**
 * Shared bookkeeping for the columnar copy-on-write variant storage: the clone parentage (each variant
 * points at the variant it was cloned from), which variants are copy-on-write ({@code STRUCTURAL} clones,
 * owning only their diverged rows), and the derived per-variant list of copy-on-write children (the
 * variants a write must freeze rows into to preserve the clone snapshot).
 *
 * <p>This folds the clone parentage together with the structural-variant marking previously kept in
 * {@link VariantManagerImpl}. One instance per network,
 * owned by the {@link VariantManagerImpl} and consulted by every {@link VariantColumnStore}.</p>
 *
 * <p>The {@link #isActive()} gate is the copy-on-write master switch: it is {@code true} while at least one
 * copy-on-write variant exists. While it is {@code false} — every network that never clones with
 * {@code VariantCloneStrategy.STRUCTURAL} — the stores take their plain dense path and never consult the
 * rest of this class.</p>
 *
 * <p>Thread-safety follows the {@link com.powsybl.iidm.network.VariantManager} contract: all mutations
 * happen during variant structural operations, on the main thread only. {@code active} is {@code volatile}
 * so concurrent readers on pre-allocated variants observe the gate; the arrays behind it are only read on
 * paths already guarded by the gate and are safely published by the same external synchronization that
 * publishes the variants themselves.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
final class VariantCowState {

    static final int NO_PARENT = -1;

    private static final int[] NONE = {};

    // true while at least one copy-on-write (STRUCTURAL) variant exists — the master gate
    private volatile boolean active;

    // all indexed by variant index
    private boolean[] cow = new boolean[0];
    private int[] parent = new int[0];
    private int[][] cowChildren = new int[0][];

    /** True while at least one copy-on-write variant exists; stores bypass all of this while false. */
    boolean isActive() {
        return active;
    }

    /** Whether {@code variantIndex} is a copy-on-write ({@code STRUCTURAL}) variant. */
    boolean isCow(int variantIndex) {
        return variantIndex >= 0 && variantIndex < cow.length && cow[variantIndex];
    }

    /** The variant {@code variantIndex} was cloned from, or {@link #NO_PARENT} for a root. */
    int getParent(int variantIndex) {
        return variantIndex >= 0 && variantIndex < parent.length ? parent[variantIndex] : NO_PARENT;
    }

    /** The copy-on-write children of {@code variantIndex} (the variants a write to it must freeze into). */
    int[] getCowChildren(int variantIndex) {
        if (variantIndex < 0 || variantIndex >= cowChildren.length) {
            return NONE;
        }
        int[] children = cowChildren[variantIndex];
        return children != null ? children : NONE;
    }

    /**
     * Record a clone: {@code child} was cloned from {@code parentIndex} with the given strategy. Called for
     * every clone (whatever the strategy) before the per-variant state owners are driven, so the stores see
     * a consistent parentage while cloning. Overwriting an existing variant simply re-records it.
     */
    void recordClone(int child, int parentIndex, boolean structural) {
        ensureCapacity(Math.max(child, parentIndex) + 1);
        parent[child] = parentIndex;
        cow[child] = structural;
        rebuildDerivedState();
    }

    /**
     * Forget a removed variant: its children are re-parented onto its own parent and its marks are cleared,
     * so the index can be recycled. State inherited from it must have been frozen into its copy-on-write
     * children <em>before</em> this call (see {@code NetworkImpl#materializeCowInheritorsOf}).
     */
    void forgetVariant(int removed) {
        if (removed < 0 || removed >= parent.length) {
            return;
        }
        int grandParent = parent[removed];
        for (int v = 0; v < parent.length; v++) {
            if (parent[v] == removed) {
                parent[v] = grandParent;
            }
        }
        parent[removed] = NO_PARENT;
        cow[removed] = false;
        rebuildDerivedState();
    }

    private void ensureCapacity(int size) {
        if (size <= cow.length) {
            return;
        }
        int oldLength = cow.length;
        int newLength = Math.max(size, oldLength * 2);
        cow = Arrays.copyOf(cow, newLength);
        int[] newParent = Arrays.copyOf(parent, newLength);
        Arrays.fill(newParent, oldLength, newLength, NO_PARENT);
        parent = newParent;
    }

    // Rebuild the cow-children lists and the master gate. Runs on the main thread during variant
    // structural operations only; variant counts are small, so a full rebuild is simpler than maintaining
    // the lists incrementally.
    private void rebuildDerivedState() {
        int[][] children = new int[parent.length][];
        int[] counts = new int[parent.length];
        boolean anyCow = false;
        for (int v = 0; v < parent.length; v++) {
            if (cow[v]) {
                anyCow = true;
                if (parent[v] >= 0) {
                    counts[parent[v]]++;
                }
            }
        }
        for (int p = 0; p < counts.length; p++) {
            children[p] = counts[p] == 0 ? NONE : new int[counts[p]];
        }
        Arrays.fill(counts, 0);
        for (int v = 0; v < parent.length; v++) {
            if (cow[v] && parent[v] >= 0) {
                children[parent[v]][counts[parent[v]]++] = v;
            }
        }
        cowChildren = children;
        active = anyCow;
    }
}
