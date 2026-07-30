/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Shared bookkeeping for the columnar copy-on-write variant storage: the clone parentage (each variant
 * points at the variant it was cloned from), which variants are copy-on-write (every clone -- they own only
 * their diverged rows), and the derived per-variant list of copy-on-write children (the variants a write
 * must freeze rows into to preserve the clone snapshot).
 *
 * <p>This is the in-memory counterpart of network-store's {@code fullVariantNum}: the initial variant is
 * the dense (full) one, every clone is a partial variant holding only what it diverges. One instance per
 * network,
 * owned by the {@link VariantManagerImpl} and consulted by every {@link VariantColumnStore}.</p>
 *
 * <p>The {@link #isActive()} gate is the copy-on-write master switch: it is {@code true} while at least one
 * copy-on-write variant exists, i.e. while the network has more than the initial variant. While it is
 * {@code false} — every single-variant network — the stores take their plain dense path and never consult
 * the rest of this class, and structure is not variant-scoped at all.</p>
 *
 * <p>Thread-safety: the parentage/marks/derived-children are bundled into a single immutable {@link State}
 * snapshot published through one {@code volatile} reference. Readers ({@link #isActive()}, {@link #isCow},
 * {@link #getParent}, {@link #getCowChildren}) take one volatile read and observe a consistent tuple with no
 * lock, so they are safe on worker threads even while another thread records or forgets a clone. Mutators
 * ({@link #recordClone}, {@link #forgetVariant}) build a fresh {@code State} from the current one and swap it
 * in with a single volatile write; the arrays inside a published {@code State} are never mutated afterwards.
 * Mutators must be externally serialized (they are: {@link VariantManagerImpl} runs every clone/remove under
 * its variant lock), so their read-modify-write of the snapshot cannot interleave.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
final class VariantCowState {

    static final int NO_PARENT = -1;

    private static final int[] NONE = {};

    /**
     * Immutable snapshot of the whole bookkeeping, published atomically through the {@code state} volatile.
     * Every array is fully built before publication and never mutated afterwards, so a reader holding a
     * reference always sees a self-consistent (parent, cow, cowChildren, active) tuple.
     */
    private static final class State {
        final boolean[] cow;
        final int[] parent;
        final int[][] cowChildren;
        final boolean active;

        State(boolean[] cow, int[] parent, int[][] cowChildren, boolean active) {
            this.cow = cow;
            this.parent = parent;
            this.cowChildren = cowChildren;
            this.active = active;
        }
    }

    private static final State EMPTY = new State(new boolean[0], new int[0], new int[0][], false);

    private volatile State state = EMPTY;

    private static final int[] NO_FROZEN = {};

    // Variant indexes that are frozen concurrent-clone bases: a variant becomes frozen when a variant is
    // forked from it while multi-thread access is enabled, and must not be written once the network is
    // genuinely shared between threads (writing it would freeze state into every worker variant that
    // inherits from it, racing their own writes). Published through one volatile reference; readers on the
    // write hot path take a single volatile read that short-circuits on the empty (common) case.
    private volatile int[] frozenBases = NO_FROZEN;

    // Whether the network is actually being touched by more than one thread (see
    // VariantContext#isSharedAcrossThreads). Enabling multi-thread access only declares the intent; the
    // freeze-on-write race needs a second thread to exist, so the write guard below waits for the fact.
    private volatile BooleanSupplier sharedAcrossThreads = () -> false;

    /** True while at least one copy-on-write variant exists; stores bypass all of this while false. */
    boolean isActive() {
        return state.active;
    }

    /** Whether {@code variantIndex} is a copy-on-write (partial) variant, i.e. any variant but the initial one. */
    boolean isCow(int variantIndex) {
        boolean[] cow = state.cow;
        return variantIndex >= 0 && variantIndex < cow.length && cow[variantIndex];
    }

    /** The variant {@code variantIndex} was cloned from, or {@link #NO_PARENT} for a root. */
    int getParent(int variantIndex) {
        int[] parent = state.parent;
        return variantIndex >= 0 && variantIndex < parent.length ? parent[variantIndex] : NO_PARENT;
    }

    /** The copy-on-write children of {@code variantIndex} (the variants a write to it must freeze into). */
    int[] getCowChildren(int variantIndex) {
        int[][] cowChildren = state.cowChildren;
        if (variantIndex < 0 || variantIndex >= cowChildren.length) {
            return NONE;
        }
        int[] children = cowChildren[variantIndex];
        return children != null ? children : NONE;
    }

    /**
     * Record a clone: {@code child} was cloned from {@code parentIndex}. Called before the per-variant state
     * owners are driven, so the stores see a consistent parentage while cloning. Overwriting an existing
     * variant simply re-records it.
     *
     * <p>Must be called under the {@link VariantManagerImpl} variant lock (serialized with other mutators).</p>
     */
    void recordClone(int child, int parentIndex) {
        State cur = state;
        int size = Math.max(cur.parent.length, Math.max(child, parentIndex) + 1);
        boolean[] cow = Arrays.copyOf(cur.cow, size);
        int[] parent = Arrays.copyOf(cur.parent, size);
        // freshly grown parent slots have no parent yet (Arrays.copyOf pads int[] with 0, a valid index)
        Arrays.fill(parent, cur.parent.length, size, NO_PARENT);
        parent[child] = parentIndex;
        cow[child] = true;
        state = build(cow, parent);
    }

    /**
     * Forget a removed variant: its children are re-parented onto its own parent and its marks are cleared,
     * so the index can be recycled. State inherited from it must have been frozen into its copy-on-write
     * children <em>before</em> this call (see {@code NetworkImpl#materializeCowInheritorsOf}).
     *
     * <p>Must be called under the {@link VariantManagerImpl} variant lock (serialized with other mutators).</p>
     */
    void forgetVariant(int removed) {
        State cur = state;
        if (removed < 0 || removed >= cur.parent.length) {
            return;
        }
        boolean[] cow = cur.cow.clone();
        int[] parent = cur.parent.clone();
        int grandParent = parent[removed];
        for (int v = 0; v < parent.length; v++) {
            if (parent[v] == removed) {
                parent[v] = grandParent;
            }
        }
        parent[removed] = NO_PARENT;
        cow[removed] = false;
        state = build(cow, parent);
    }

    /** Wire the probe telling whether more than one thread is actually accessing the network. */
    void setSharedAcrossThreadsProbe(BooleanSupplier probe) {
        this.sharedAcrossThreads = probe;
    }

    /** Whether {@code variant} has been recorded as a concurrent-clone base. */
    boolean isFrozenBase(int variant) {
        int[] fb = frozenBases;
        for (int b : fb) {
            if (b == variant) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mark {@code variant} as a frozen base for the current parallel region. Idempotent. Must be called under
     * the {@link VariantManagerImpl} variant lock (serialized with other mutators).
     */
    void freezeBase(int variant) {
        int[] fb = frozenBases;
        for (int b : fb) {
            if (b == variant) {
                return;
            }
        }
        int[] next = Arrays.copyOf(fb, fb.length + 1);
        next[fb.length] = variant;
        frozenBases = next;
    }

    /** Clear all frozen bases (the parallel region has ended). */
    void clearFrozenBases() {
        frozenBases = NO_FROZEN;
    }

    /**
     * Throw if {@code variant} is the shared base of clones that other threads are working on. Called on the
     * columnar write path so an unsafe write fails fast instead of racing the variants that inherit from it:
     * writing a base pushes its current value down into every copy-on-write child that has not diverged yet,
     * and that push-down races a worker writing the same child.
     *
     * <p>Both conditions are needed. Being a fork source is not enough — the long-standing pattern of
     * enabling multi-thread access, cloning, and preparing the variants on the main thread before handing
     * them to workers writes the base with no other thread in sight, and stays legal. It is the second thread
     * binding a working variant that makes the push-down unsafe.</p>
     */
    void checkWritable(int variant) {
        if (isFrozenBase(variant) && sharedAcrossThreads.getAsBoolean()) {
            throw new PowsyblException("Variant index " + variant + " is the shared base of variants that "
                    + "other threads are working on, and must not be written while they do: writing it would "
                    + "freeze state into the variants that fork from it, racing their own writes. Write only "
                    + "the per-worker variants during the parallel region.");
        }
    }

    // Build a fresh immutable snapshot (derived cow-children lists + master gate) from the given parent/cow
    // arrays. The arrays are taken over by the returned State and must not be mutated by the caller after.
    // Variant counts are small, so a full rebuild per mutation is simpler than maintaining the lists
    // incrementally.
    private static State build(boolean[] cow, int[] parent) {
        int n = parent.length;
        int[] counts = new int[n];
        boolean anyCow = false;
        for (int v = 0; v < n; v++) {
            if (cow[v]) {
                anyCow = true;
                if (parent[v] >= 0) {
                    counts[parent[v]]++;
                }
            }
        }
        int[][] children = new int[n][];
        for (int p = 0; p < n; p++) {
            children[p] = counts[p] == 0 ? NONE : new int[counts[p]];
        }
        int[] fill = new int[n];
        for (int v = 0; v < n; v++) {
            if (cow[v] && parent[v] >= 0) {
                int p = parent[v];
                children[p][fill[p]++] = v;
            }
        }
        return new State(cow, parent, children, anyCow);
    }
}
