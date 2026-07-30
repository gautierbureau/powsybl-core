/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * A per-variant array of references that can be grown while other threads read and write it, and that sizes
 * itself on demand rather than depending on having been extended in time.
 *
 * <h2>Why not an ArrayList</h2>
 *
 * <p>Growing an {@code ArrayList} reallocates the backing array and copies the values across, so a write into
 * the old array that lands during the copy is silently lost. Here the values live in fixed-size chunks and
 * growth only ever <em>appends</em> chunks: an existing chunk is never moved, copied or replaced. A write to
 * variant {@code v} lands in the chunk holding {@code v}, and every reader — through the old spine or the new
 * one — sees that same chunk object. Only the spine is copied, and it is published through a
 * {@code volatile}. This is the trick the columnar variant stores use for their row chunks; see
 * {@code NumericVariantStore}.</p>
 *
 * <h2>Why slots materialise on read</h2>
 *
 * <p>An object sizes its per-variant state in its constructor and publishes itself in the network index as a
 * separate, later step. A clone that runs between those two points grows every object in its stateful-objects
 * snapshot — which cannot contain the new object, as it is not published yet — and leaves it one slot short.
 * Coordinating the two is awkward: adders attach a terminal to the topology <em>before</em> publishing, so
 * the object is reachable earlier than any single choke point, and repairing after the growth misses an
 * object published just after the repair ran.</p>
 *
 * <p>So a missing slot is made unobservable instead: {@link #get} materialises one on demand. The value it
 * materialises is the <b>construction-time initial value</b>, and that is the only defensible choice, by this
 * invariant:</p>
 *
 * <blockquote>A slot for variant {@code m} is missing only if {@code m} was forked by a clone whose snapshot
 * predated this object's publication — that is, only if the object did not yet exist, as far as the network
 * was concerned, when {@code m} was forked.</blockquote>
 *
 * <p>which holds because once an object is published it is in every later snapshot, so every later clone
 * extends it. A missing slot therefore always corresponds to a fork at which the object was newborn: there is
 * no value-at-fork-time to inherit, and every slot held the initial value then. Inheriting from the parent
 * variant instead would be <em>wrong</em> — by materialisation time the parent may have been written since
 * the fork, and copying it would leak post-fork divergence into a variant that must not see it.</p>
 *
 * <p>The consequence is what makes this safe: materialisation is <b>time-invariant and idempotent</b>, so a
 * lazily materialised slot and an eagerly extended one are indistinguishable, and lazy sizing cannot violate
 * the snapshot semantics of a variant. Objects present at clone time still take the eager path in
 * {@link #grow} and inherit from the source variant exactly as before.</p>
 *
 * <p>{@code null} is a legitimate stored value for both users of this class, so absence cannot be encoded as
 * null and a private sentinel is used instead.</p>
 *
 * <p>Thread-safety: concurrent {@link #set} on <em>distinct</em> variants is safe, as is {@code set}
 * concurrent with {@link #grow} or with materialisation. Concurrent {@code set} on the <em>same</em> variant
 * is not, and does not need to be — a variant is only ever written by the thread that owns it. Growth and
 * materialisation are serialised on this instance: two threads growing concurrently could otherwise publish
 * two different chunk objects for the same index, and a write through one would be invisible through the
 * other, breaking the chunk-stability property everything above rests on.</p>
 *
 * @param <T> the per-variant value type
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
final class VariantRefArray<T> {

    private static final int CHUNK = 8;
    private static final int SHIFT = 3;
    private static final int MASK = CHUNK - 1;

    /** Marks a slot that has never been given a value — distinct from a stored {@code null}. */
    private static final Object UNSET = new Object();

    // The spine. Chunks it points at are mutated in place and never replaced, so growth only publishes a
    // longer spine and can never lose a concurrent write.
    private volatile Object[][] chunks;

    // The value a slot materialises to; see the class javadoc for why it is the initial value and not the
    // parent variant's. Retained for the lifetime of the array.
    private final Supplier<T> initialValue;

    // Number of eagerly-sized variant slots. Only touched by the variant lifecycle (grow/shrink), which runs
    // under the variant lock; reads do not consult it, they go by what the slot holds.
    private int size;

    VariantRefArray(int size, Supplier<T> initialValue) {
        this.initialValue = initialValue;
        this.chunks = new Object[0][];
        this.size = 0;
        grow(size, initialValue);
    }

    @SuppressWarnings("unchecked")
    T get(int variant) {
        Object[][] spine = chunks;
        int chunk = variant >>> SHIFT;
        if (chunk >= spine.length) {
            return materialize(variant);
        }
        Object value = spine[chunk][variant & MASK];
        // both branches below are perfectly predicted once the array has been sized normally
        return value == UNSET ? materialize(variant) : (T) value;
    }

    /** Give a never-set slot its value, growing the spine first if the variant is past its end. */
    @SuppressWarnings("unchecked")
    private synchronized T materialize(int variant) {
        Object[][] spine = ensureAddressable(variant);
        Object current = spine[variant >>> SHIFT][variant & MASK];
        if (current != UNSET) {
            return (T) current; // another thread got there first
        }
        T value = initialValue.get();
        spine[variant >>> SHIFT][variant & MASK] = value;
        chunks = spine;
        return value;
    }

    void set(int variant, T value) {
        // one volatile read, then a plain store into a chunk no growth will ever replace
        Object[][] spine = chunks;
        if (variant >>> SHIFT >= spine.length) {
            spine = growSpine(variant);
        }
        spine[variant >>> SHIFT][variant & MASK] = value;
    }

    /** Set the slot and return what it held, for callers that report the previous value. */
    T getAndSet(int variant, T value) {
        T old = get(variant); // materialises first, so the reported previous value is never the sentinel
        set(variant, value);
        return old;
    }

    int size() {
        return size;
    }

    /** Append {@code number} slots holding {@code value}'s result — the eager path, driven by a clone. */
    synchronized void grow(int number, Supplier<T> value) {
        int required = size + number;
        Object[][] spine = required == 0 ? chunks : ensureAddressable(required - 1);
        for (int v = size; v < required; v++) {
            spine[v >>> SHIFT][v & MASK] = value.get();
        }
        // publish the filled slots and the longer spine together
        chunks = spine;
        size = required;
    }

    /** Drop the last {@code number} slots. Chunks left empty are kept; the spine never shrinks. */
    synchronized void shrink(int number) {
        int target = size - number;
        Object[][] spine = chunks;
        for (int v = target; v < size; v++) {
            spine[v >>> SHIFT][v & MASK] = UNSET;
        }
        chunks = spine;
        size = target;
    }

    /**
     * Clear the slot of a removed variant. It reverts to unset rather than null, so that reading the index
     * before it is handed out again materialises a fresh value instead of returning a stale one.
     */
    synchronized void clear(int variant) {
        Object[][] spine = ensureAddressable(variant);
        spine[variant >>> SHIFT][variant & MASK] = UNSET;
        chunks = spine;
    }

    private synchronized Object[][] growSpine(int variant) {
        Object[][] spine = ensureAddressable(variant);
        chunks = spine;
        return spine;
    }

    /**
     * Return a spine in which {@code variant} is addressable, appending unset chunks if needed. Existing
     * chunks are carried over by reference, so writes in flight against them stay visible through the result.
     * Callers must publish the returned spine.
     */
    private Object[][] ensureAddressable(int variant) {
        Object[][] spine = chunks;
        int needed = (variant >>> SHIFT) + 1;
        if (spine.length >= needed) {
            return spine;
        }
        Object[][] grown = Arrays.copyOf(spine, needed);
        for (int c = spine.length; c < needed; c++) {
            Object[] fresh = new Object[CHUNK];
            Arrays.fill(fresh, UNSET);
            grown[c] = fresh;
        }
        return grown;
    }
}
