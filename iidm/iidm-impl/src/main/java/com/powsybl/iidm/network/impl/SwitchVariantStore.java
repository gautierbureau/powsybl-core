/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Columnar (structure-of-arrays) store for the variant-dependent <em>topology</em> of every switch in a
 * network: its {@code open} and {@code retained} state.
 *
 * <p>The second columnar variant-storage type. Switch open/retained is per-variant topology
 * (unlike terminal p/q, which is a computed characteristic) and is the state that security-analysis
 * contingencies mutate, so it is on the path of the real variant-cloning workload. It follows the same flat
 * variant-major layout as {@link TerminalVariantStore}: {@code open[variant][rowChunk][slot]}, so a clone
 * is a couple of {@link System#arraycopy} calls instead of one method call per switch.</p>
 *
 * <p>Cloned variants are stored copy-on-write, exactly as in
 * {@link TerminalVariantStore}: {@link #extend} copies nothing (O(1)); reads resolve through the
 * clone parentage ({@link VariantCowState}); rows diverge per write, and a write to a variant first freezes
 * the touched row into the copy-on-write children still inheriting it. The
 * {@link VariantCowState#isActive()} gate keeps every network without a structural variant on the plain
 * dense path. See {@link TerminalVariantStore} for the copy-on-write thread-safety caveat.</p>
 *
 * <p>{@code boolean[]} (one byte per flag) is used here for clarity and to mirror the terminal store; a
 * further compaction would bit-pack each variant band into a {@code long[]} (one bit per switch), making a
 * clone an even smaller bitset copy. That is left as a follow-up.</p>
 *
 * <p>Thread-safety follows the {@link com.powsybl.iidm.network.VariantManager} contract as before: structural
 * changes happen on the main thread only; pre-allocated variants are read/written concurrently, each thread
 * on its own band. Rows are monotonic (no free list): a removed switch leaves a dead row,
 * which is correct (never read) but grows with switch churn. Recycling would need a {@code removed} guard on
 * {@link SwitchImpl} to be safe, unlike terminals whose reads are already guarded.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class SwitchVariantStore implements VariantColumnStore {

    // Rows are stored in fixed-size chunks: open(variant v, row r) = open[v][r >>> SHIFT][r & MASK].
    // Growing either axis appends to a directory of references and never moves a chunk, so a reader holding
    // one is never invalidated -- which is what lets equipment be created while worker threads are reading.
    // The chunk size trades a little slack on small networks against directory size on large ones; the extra
    // indirection measured at ~0.1 ns per read, against ~27 ns for the getP()/getQ() call around it.
    private static final int CHUNK = 128;
    private static final int SHIFT = Integer.numberOfTrailingZeros(CHUNK);
    private static final int MASK = CHUNK - 1;

    private volatile boolean[][][] open;
    private volatile boolean[][][] retained;

    private int rowCount;
    private int variantSize;    // number of live variant indexes (dense + copy-on-write)
    private int flatSize;       // high-water variant index (+1) physically held by the flat arrays
    private int variantCapacity;

    // shared copy-on-write bookkeeping (parentage, structural marks, master gate)
    private final VariantCowState cowState;

    // sparse bands of the copy-on-write variants, indexed by variant; see TerminalVariantStore
    private volatile CowBand[] cowBands = new CowBand[0];

    /** One copy-on-write variant's diverged rows, chunked like the dense storage. */
    private static final class CowBand {
        final BitSet materialized = new BitSet();
        volatile boolean[][] open = new boolean[0][];
        volatile boolean[][] retained = new boolean[0][];

        boolean open(int row) {
            return open[row >>> SHIFT][row & MASK];
        }

        boolean retained(int row) {
            return retained[row >>> SHIFT][row & MASK];
        }

        void setOpen(int row, boolean value) {
            open[row >>> SHIFT][row & MASK] = value;
        }

        void setRetained(int row, boolean value) {
            retained[row >>> SHIFT][row & MASK] = value;
        }

        void ensureRow(int row) {
            open = growChunks(open, row);
            retained = growChunks(retained, row);
        }
    }

    /** Append chunks until {@code row} is addressable. Existing chunks are never moved or copied. */
    private static boolean[][] growChunks(boolean[][] chunks, int row) {
        int needed = (row >>> SHIFT) + 1;
        if (chunks.length >= needed) {
            return chunks;
        }
        boolean[][] grown = Arrays.copyOf(chunks, needed);
        for (int c = chunks.length; c < needed; c++) {
            grown[c] = new boolean[CHUNK];
        }
        return grown;
    }

    SwitchVariantStore(int variantArraySize, VariantCowState cowState) {
        this.cowState = cowState;
        this.rowCount = 0;
        this.variantSize = variantArraySize;
        this.flatSize = variantArraySize;
        this.variantCapacity = Math.max(variantArraySize, 1);
        this.open = newBands(variantCapacity);
        this.retained = newBands(variantCapacity);
        // Pre-size the copy-on-write band table to the current variant count so it is never grown on a worker
        // thread (see NumericVariantStore for the full rationale).
        this.cowBands = new CowBand[variantSize];
    }

    /**
     * Allocate a row for a new switch, initialised with the given open/retained state in every live variant
     * band (a new switch has the same state in all variants, as the previous per-switch constructor did).
     */
    synchronized int allocateRow(boolean openValue, boolean retainedValue) {
        int row = rowCount++;
        ensureRow(row);
        boolean[][][] od = open;
        boolean[][][] rd = retained;
        for (int v = 0; v < flatSize; v++) {
            od[v][row >>> SHIFT][row & MASK] = openValue;
            rd[v][row >>> SHIFT][row & MASK] = retainedValue;
        }
        // a fresh row was never materialised in any copy-on-write band, so it resolves to the dense value
        return row;
    }

    private static boolean[][][] newBands(int variants) {
        boolean[][][] bands = new boolean[variants][][];
        for (int v = 0; v < variants; v++) {
            bands[v] = new boolean[0][];
        }
        return bands;
    }

    /** Make {@code row} addressable in every dense band and in every copy-on-write band. */
    private void ensureRow(int row) {
        boolean[][][] od = open;
        boolean[][][] rd = retained;
        for (int v = 0; v < od.length; v++) {
            od[v] = growChunks(od[v], row);
            rd[v] = growChunks(rd[v], row);
        }
        open = od;
        retained = rd;
        CowBand[] bands = cowBands;
        for (CowBand band : bands) {
            if (band != null) {
                band.ensureRow(row);
            }
        }
        cowBands = bands;
    }

    private void ensureVariantCapacity(int required) {
        if (required <= variantCapacity) {
            return;
        }
        int newCapacity = Math.max(required, variantCapacity * 2);
        boolean[][][] od = Arrays.copyOf(open, newCapacity);
        boolean[][][] rd = Arrays.copyOf(retained, newCapacity);
        for (int v = variantCapacity; v < newCapacity; v++) {
            od[v] = new boolean[0][];
            rd[v] = new boolean[0][];
            for (int row = 0; row < rowCount; row++) {
                od[v] = growChunks(od[v], row);
                rd[v] = growChunks(rd[v], row);
            }
        }
        open = od;
        retained = rd;
        variantCapacity = newCapacity;
    }

    boolean getOpen(int variantIndex, int row) {
        if (!cowState.isActive()) {
            return open[variantIndex][row >>> SHIFT][row & MASK]; // FAST PATH: no copy-on-write variant
        }
        int v = resolve(variantIndex, row);
        CowBand band = bandOf(v);
        return band == null ? open[v][row >>> SHIFT][row & MASK] : band.open(row);
    }

    boolean setOpen(int variantIndex, int row, boolean value) {
        if (!cowState.isActive()) { // FAST PATH
            boolean[] chunk = open[variantIndex][row >>> SHIFT];
            boolean old = chunk[row & MASK];
            chunk[row & MASK] = value;
            return old;
        }
        freezeInheritors(variantIndex, row);
        if (cowState.isCow(variantIndex)) {
            CowBand band = materializeRow(variantIndex, row);
            boolean old = band.open(row);
            band.setOpen(row, value);
            return old;
        }
        boolean[] chunk = open[variantIndex][row >>> SHIFT];
        boolean old = chunk[row & MASK];
        chunk[row & MASK] = value;
        return old;
    }

    boolean getRetained(int variantIndex, int row) {
        if (!cowState.isActive()) {
            return retained[variantIndex][row >>> SHIFT][row & MASK]; // FAST PATH
        }
        int v = resolve(variantIndex, row);
        CowBand band = bandOf(v);
        return band == null ? retained[v][row >>> SHIFT][row & MASK] : band.retained(row);
    }

    boolean setRetained(int variantIndex, int row, boolean value) {
        if (!cowState.isActive()) { // FAST PATH
            boolean[] chunk = retained[variantIndex][row >>> SHIFT];
            boolean old = chunk[row & MASK];
            chunk[row & MASK] = value;
            return old;
        }
        freezeInheritors(variantIndex, row);
        if (cowState.isCow(variantIndex)) {
            CowBand band = materializeRow(variantIndex, row);
            boolean old = band.retained(row);
            band.setRetained(row, value);
            return old;
        }
        boolean[] chunk = retained[variantIndex][row >>> SHIFT];
        boolean old = chunk[row & MASK];
        chunk[row & MASK] = value;
        return old;
    }

    /**
     * Allocate a row initialised from single-variant state, for a switch moved between networks (merge/detach).
     */
    int importRow(boolean openValue, boolean retainedValue) {
        return allocateRow(openValue, retainedValue);
    }

    // --- copy-on-write machinery (only reached while the gate is active); see TerminalVariantStore ---

    private int resolve(int variantIndex, int row) {
        int v = variantIndex;
        while (cowState.isCow(v)) {
            CowBand band = bandOf(v);
            if (band != null && band.materialized.get(row)) {
                return v;
            }
            v = cowState.getParent(v);
        }
        return v;
    }

    private CowBand bandOf(int variantIndex) {
        CowBand[] bands = cowBands;
        return variantIndex >= 0 && variantIndex < bands.length ? bands[variantIndex] : null;
    }

    private void freezeInheritors(int variantIndex, int row) {
        cowState.checkWritable(variantIndex);
        for (int child : cowState.getCowChildren(variantIndex)) {
            CowBand band = bandOf(child);
            if (band == null || !band.materialized.get(row)) {
                materializeRow(child, row);
            }
        }
    }

    private CowBand materializeRow(int variantIndex, int row) {
        CowBand band = ensureBand(variantIndex);
        if (!band.materialized.get(row)) {
            int src = resolve(cowState.getParent(variantIndex), row);
            CowBand srcBand = bandOf(src);
            if (srcBand == null) {
                band.setOpen(row, open[src][row >>> SHIFT][row & MASK]);
                band.setRetained(row, retained[src][row >>> SHIFT][row & MASK]);
            } else {
                band.setOpen(row, srcBand.open(row));
                band.setRetained(row, srcBand.retained(row));
            }
            band.materialized.set(row);
            publishCowBands();
        }
        return band;
    }

    private CowBand ensureBand(int variantIndex) {
        CowBand[] bands = cowBands;
        if (variantIndex >= bands.length) {
            // only possible on the main thread (structural operations size the table before workers run)
            bands = Arrays.copyOf(bands, Math.max(variantIndex + 1, variantSize));
        }
        CowBand band = bands[variantIndex];
        if (band == null) {
            band = new CowBand();
            for (int row = 0; row < rowCount; row++) {
                band.ensureRow(row);
            }
            bands[variantIndex] = band;
            cowBands = bands;
        }
        return band;
    }

    private void publishCowBands() {
        CowBand[] bands = cowBands;
        cowBands = bands;
    }

    /** Number of rows the given variant has diverged (materialised) in its copy-on-write band. Test hook. */
    int cowRowsMaterialized(int variantIndex) {
        CowBand band = bandOf(variantIndex);
        return band == null ? 0 : band.materialized.cardinality();
    }

    // --- structural changes, driven once per operation by NetworkImpl (main thread only) ---

    @Override
    // synchronized on the store, like allocateRow: growing the variant dimension replaces each band's
    // chunk spine, so it must not interleave with a row allocation appending chunks to one of them
    public synchronized void extend(int number, int sourceIndex) {
        // O(1): the new copy-on-write variants own no rows and inherit through the parentage
        variantSize += number;
        CowBand[] bands = cowBands;
        if (bands.length < variantSize) {
            cowBands = Arrays.copyOf(bands, variantSize);
        }
    }

    @Override
    public void reduce(int number) {
        variantSize -= number;
        flatSize = Math.min(flatSize, variantSize);
        CowBand[] bands = cowBands;
        boolean changed = false;
        for (int v = variantSize; v < bands.length; v++) {
            if (bands[v] != null) {
                bands[v] = null;
                changed = true;
            }
        }
        if (changed) {
            cowBands = bands;
        }
    }

    @Override
    public void delete(int index) {
        // the flat band is left in place (overwritten if the index is recycled by allocate()); a
        // copy-on-write band is dropped so its diverged rows can be garbage collected
        dropCowBand(index);
    }

    @Override
    public void allocate(int[] indexes, int sourceIndex) {
        // O(1) per band: recycled/overwritten indexes become fresh copy-on-write variants owning no rows
        CowBand[] bands = cowBands;
        int required = variantSize;
        for (int index : indexes) {
            required = Math.max(required, index + 1);
        }
        if (bands.length < required) {
            bands = Arrays.copyOf(bands, required);
        }
        for (int index : indexes) {
            bands[index] = null;
        }
        cowBands = bands;
    }

    @Override
    public void materializeInheritors(int variantIndex) {
        for (int child : cowState.getCowChildren(variantIndex)) {
            CowBand band = ensureBand(child);
            for (int row = 0; row < rowCount; row++) {
                if (!band.materialized.get(row)) {
                    int src = resolve(cowState.getParent(child), row);
                    CowBand srcBand = bandOf(src);
                    band.setOpen(row, srcBand == null ? open[src][row >>> SHIFT][row & MASK] : srcBand.open(row));
                    band.setRetained(row, srcBand == null ? retained[src][row >>> SHIFT][row & MASK] : srcBand.retained(row));
                    band.materialized.set(row);
                }
            }
        }
        publishCowBands();
    }

    private void dropCowBand(int index) {
        CowBand[] bands = cowBands;
        if (index < bands.length && bands[index] != null) {
            bands[index] = null;
            cowBands = bands;
        }
    }
}
