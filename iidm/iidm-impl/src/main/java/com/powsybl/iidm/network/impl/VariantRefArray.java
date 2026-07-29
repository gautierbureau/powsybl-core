/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * A per-variant array of references that can be grown while other threads read and write it.
 *
 * <p>The obvious implementation — an {@code ArrayList} indexed by variant, which is what the objects using
 * this used to hold — cannot do that. Growing it reallocates the backing array and copies the values across,
 * so a write into the old array that lands during the copy is silently lost. That is fine while variants are
 * only ever created on the main thread, and it is one of the reasons {@code preAllocateVariants} had to be a
 * hard capacity contract rather than a performance hint.</p>
 *
 * <p>So the values live in fixed-size chunks and growth only ever <em>appends</em> chunks: an existing chunk
 * is never moved, copied or replaced. A write to variant {@code v} lands in the chunk holding {@code v}, and
 * every reader — through the old spine or the new one — sees that same chunk object. Only the spine is
 * copied, and it is published through a {@code volatile}, so a reader takes one volatile read and then two
 * array accesses. This is the same trick the columnar variant stores use for their row chunks; see
 * {@code NumericVariantStore}.</p>
 *
 * <p>Chunks are small ({@value #CHUNK}) because most networks have a single variant and one chunk is
 * allocated per object regardless: the size trades a little slack per object against how often the spine
 * grows, and variant counts are small enough that the spine stays one or two entries in practice.</p>
 *
 * <p>Thread-safety: concurrent {@link #set} on <em>distinct</em> variants is safe, as is {@link #set}
 * concurrent with {@link #grow}. Concurrent {@code set} on the <em>same</em> variant is not, and does not
 * need to be — a variant is only ever written by the thread that owns it.</p>
 *
 * @param <T> the per-variant value type
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
final class VariantRefArray<T> {

    private static final int CHUNK = 8;
    private static final int SHIFT = 3;
    private static final int MASK = CHUNK - 1;

    // The spine. Chunks it points at are mutated in place and never replaced, so growth only publishes a
    // longer spine and can never lose a concurrent write.
    private volatile Object[][] chunks;

    // Number of live variant slots. Only ever read/written under the variant lock (grow/shrink are variant
    // lifecycle operations), so it needs no publication of its own.
    private int size;

    /** Create an array of {@code size} slots, each initialised by {@code init} from its own index. */
    VariantRefArray(int size, IntFunction<T> init) {
        this.chunks = new Object[0][];
        this.size = 0;
        grow(size, i -> init.apply(i));
    }

    @SuppressWarnings("unchecked")
    T get(int variant) {
        return (T) chunks[variant >>> SHIFT][variant & MASK];
    }

    void set(int variant, T value) {
        // one volatile read, then a plain store into a chunk no growth will ever replace
        chunks[variant >>> SHIFT][variant & MASK] = value;
    }

    /** Set the slot and return what it held, for callers that report the previous value. */
    @SuppressWarnings("unchecked")
    T getAndSet(int variant, T value) {
        Object[] chunk = chunks[variant >>> SHIFT];
        int slot = variant & MASK;
        Object old = chunk[slot];
        chunk[slot] = value;
        return (T) old;
    }

    int size() {
        return size;
    }

    /** Append {@code number} slots, each initialised by {@code init} from its own (new) index. */
    void grow(int number, IntFunction<T> init) {
        int required = size + number;
        Object[][] spine = chunks;
        int needed = required == 0 ? 0 : (required - 1 >>> SHIFT) + 1;
        if (spine.length < needed) {
            // copy the spine only: every existing chunk is carried over by reference, so writes in flight
            // against them stay visible through the new spine
            Object[][] grown = Arrays.copyOf(spine, needed);
            for (int c = spine.length; c < needed; c++) {
                grown[c] = new Object[CHUNK];
            }
            spine = grown;
        }
        for (int v = size; v < required; v++) {
            spine[v >>> SHIFT][v & MASK] = init.apply(v);
        }
        // publish the filled slots and the longer spine together
        chunks = spine;
        size = required;
    }

    /** Drop the last {@code number} slots. Chunks left empty are kept; the spine never shrinks. */
    void shrink(int number) {
        int target = size - number;
        for (int v = target; v < size; v++) {
            chunks[v >>> SHIFT][v & MASK] = null;
        }
        size = target;
    }

    /** Clear the slot of a removed variant so it holds nothing until the index is reused. */
    void clear(int variant) {
        set(variant, null);
    }
}
