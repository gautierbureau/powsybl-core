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
 * variant-major layout as {@link TerminalVariantStore}: {@code open[variant * rowStride + row]}, so a clone
 * is a couple of {@link System#arraycopy} calls instead of one method call per switch.</p>
 *
 * <p>Variants cloned with {@code VariantCloneStrategy.STRUCTURAL} are stored copy-on-write, exactly as in
 * {@link TerminalVariantStore}: {@link #extendStructural} copies nothing (O(1)); reads resolve through the
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

    private static final int DEFAULT_ROW_CAPACITY = 16;

    // flat variant-major layout: open(variant v, row r) = open[v * rowStride + r]
    private volatile boolean[] open;
    private volatile boolean[] retained;

    private int rowStride;
    private int rowCount;
    private int variantSize;    // number of live variant indexes (dense + copy-on-write)
    private int flatSize;       // high-water variant index (+1) physically held by the flat arrays
    private int variantCapacity;

    // shared copy-on-write bookkeeping (parentage, structural marks, master gate)
    private final VariantCowState cowState;

    // sparse bands of the copy-on-write variants, indexed by variant; see TerminalVariantStore
    private volatile CowBand[] cowBands = new CowBand[0];

    private static final class CowBand {
        final BitSet materialized;
        final boolean[] open;
        final boolean[] retained;

        CowBand(int rowStride) {
            this.materialized = new BitSet(rowStride);
            this.open = new boolean[rowStride];
            this.retained = new boolean[rowStride];
        }

        CowBand(CowBand from, int newStride) {
            this.materialized = new BitSet(newStride);
            this.materialized.or(from.materialized);
            this.open = Arrays.copyOf(from.open, newStride);
            this.retained = Arrays.copyOf(from.retained, newStride);
        }
    }

    SwitchVariantStore(int variantArraySize, VariantCowState cowState) {
        this.cowState = cowState;
        this.rowStride = DEFAULT_ROW_CAPACITY;
        this.rowCount = 0;
        this.variantSize = variantArraySize;
        this.flatSize = variantArraySize;
        this.variantCapacity = Math.max(variantArraySize, 1);
        this.open = new boolean[variantCapacity * rowStride];
        this.retained = new boolean[variantCapacity * rowStride];
    }

    /**
     * Allocate a row for a new switch, initialised with the given open/retained state in every live variant
     * band (a new switch has the same state in all variants, as the previous per-switch constructor did).
     */
    int allocateRow(boolean openValue, boolean retainedValue) {
        if (rowCount == rowStride) {
            growRowStride();
        }
        int row = rowCount++;
        boolean[] od = open;
        boolean[] rd = retained;
        for (int v = 0; v < flatSize; v++) {
            od[v * rowStride + row] = openValue;
            rd[v * rowStride + row] = retainedValue;
        }
        // a fresh row was never materialised in any copy-on-write band, so it resolves to the dense value
        return row;
    }

    private void growRowStride() {
        int newStride = rowStride * 2;
        open = restride(open, newStride);
        retained = restride(retained, newStride);
        CowBand[] bands = cowBands;
        boolean changed = false;
        for (int v = 0; v < bands.length; v++) {
            if (bands[v] != null) {
                bands[v] = new CowBand(bands[v], newStride);
                changed = true;
            }
        }
        if (changed) {
            cowBands = bands;
        }
        rowStride = newStride;
    }

    private boolean[] restride(boolean[] data, int newStride) {
        boolean[] out = new boolean[variantCapacity * newStride];
        for (int v = 0; v < flatSize; v++) {
            System.arraycopy(data, v * rowStride, out, v * newStride, rowCount);
        }
        return out;
    }

    private void ensureVariantCapacity(int required) {
        if (required <= variantCapacity) {
            return;
        }
        int newCapacity = Math.max(required, variantCapacity * 2);
        open = Arrays.copyOf(open, newCapacity * rowStride);
        retained = Arrays.copyOf(retained, newCapacity * rowStride);
        variantCapacity = newCapacity;
    }

    boolean getOpen(int variantIndex, int row) {
        if (!cowState.isActive()) {
            return open[variantIndex * rowStride + row]; // FAST PATH: no copy-on-write variant exists
        }
        int v = resolve(variantIndex, row);
        CowBand band = bandOf(v);
        return band == null ? open[v * rowStride + row] : band.open[row];
    }

    boolean setOpen(int variantIndex, int row, boolean value) {
        if (!cowState.isActive()) { // FAST PATH
            boolean[] data = open;
            int i = variantIndex * rowStride + row;
            boolean old = data[i];
            data[i] = value;
            return old;
        }
        freezeInheritors(variantIndex, row);
        if (cowState.isCow(variantIndex)) {
            CowBand band = materializeRow(variantIndex, row);
            boolean old = band.open[row];
            band.open[row] = value;
            return old;
        }
        boolean[] data = open;
        int i = variantIndex * rowStride + row;
        boolean old = data[i];
        data[i] = value;
        return old;
    }

    boolean getRetained(int variantIndex, int row) {
        if (!cowState.isActive()) {
            return retained[variantIndex * rowStride + row]; // FAST PATH
        }
        int v = resolve(variantIndex, row);
        CowBand band = bandOf(v);
        return band == null ? retained[v * rowStride + row] : band.retained[row];
    }

    boolean setRetained(int variantIndex, int row, boolean value) {
        if (!cowState.isActive()) { // FAST PATH
            boolean[] data = retained;
            int i = variantIndex * rowStride + row;
            boolean old = data[i];
            data[i] = value;
            return old;
        }
        freezeInheritors(variantIndex, row);
        if (cowState.isCow(variantIndex)) {
            CowBand band = materializeRow(variantIndex, row);
            boolean old = band.retained[row];
            band.retained[row] = value;
            return old;
        }
        boolean[] data = retained;
        int i = variantIndex * rowStride + row;
        boolean old = data[i];
        data[i] = value;
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
                band.open[row] = open[src * rowStride + row];
                band.retained[row] = retained[src * rowStride + row];
            } else {
                band.open[row] = srcBand.open[row];
                band.retained[row] = srcBand.retained[row];
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
            band = new CowBand(rowStride);
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
    public void extend(int number, int sourceIndex) {
        int newSize = variantSize + number;
        ensureVariantCapacity(newSize);
        int stride = rowStride;
        boolean[] od = open;
        boolean[] rd = retained;
        if (!cowState.isActive() || !cowState.isCow(sourceIndex)) {
            int srcOff = sourceIndex * stride;
            for (int i = 0; i < number; i++) {
                int dstOff = (variantSize + i) * stride;
                System.arraycopy(od, srcOff, od, dstOff, rowCount);
                System.arraycopy(rd, srcOff, rd, dstOff, rowCount);
            }
        } else {
            // the source is copy-on-write: an eager (STATE_ONLY) clone of it copies its resolved band
            for (int row = 0; row < rowCount; row++) {
                int src = resolve(sourceIndex, row);
                CowBand srcBand = bandOf(src);
                boolean ov = srcBand == null ? od[src * stride + row] : srcBand.open[row];
                boolean rv = srcBand == null ? rd[src * stride + row] : srcBand.retained[row];
                for (int i = 0; i < number; i++) {
                    od[(variantSize + i) * stride + row] = ov;
                    rd[(variantSize + i) * stride + row] = rv;
                }
            }
        }
        open = od;
        retained = rd;
        variantSize = newSize;
        flatSize = Math.max(flatSize, newSize);
    }

    @Override
    public void extendStructural(int number, int sourceIndex) {
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
        int required = flatSize;
        for (int index : indexes) {
            required = Math.max(required, index + 1);
        }
        ensureVariantCapacity(required);
        int stride = rowStride;
        boolean[] od = open;
        boolean[] rd = retained;
        if (!cowState.isActive() || !cowState.isCow(sourceIndex)) {
            int srcOff = sourceIndex * stride;
            for (int index : indexes) {
                int dstOff = index * stride;
                System.arraycopy(od, srcOff, od, dstOff, rowCount);
                System.arraycopy(rd, srcOff, rd, dstOff, rowCount);
            }
        } else {
            for (int row = 0; row < rowCount; row++) {
                int src = resolve(sourceIndex, row);
                CowBand srcBand = bandOf(src);
                boolean ov = srcBand == null ? od[src * stride + row] : srcBand.open[row];
                boolean rv = srcBand == null ? rd[src * stride + row] : srcBand.retained[row];
                for (int index : indexes) {
                    od[index * stride + row] = ov;
                    rd[index * stride + row] = rv;
                }
            }
        }
        open = od;
        retained = rd;
        // the target becomes (or stays) dense: it owns its flat band, any copy-on-write past is dropped
        for (int index : indexes) {
            dropCowBand(index);
        }
        flatSize = required;
    }

    @Override
    public void allocateStructural(int[] indexes, int sourceIndex) {
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
                    band.open[row] = srcBand == null ? open[src * rowStride + row] : srcBand.open[row];
                    band.retained[row] = srcBand == null ? retained[src * rowStride + row] : srcBand.retained[row];
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
