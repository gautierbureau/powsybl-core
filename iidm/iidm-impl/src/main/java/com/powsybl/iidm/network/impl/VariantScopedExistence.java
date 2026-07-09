/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p><b>Spike v2.1a — variant-scoped existence (de-risking prototype).</b> See
 * {@code structural-variant-generic-design.md}, the v2.1 section.</p>
 *
 * <p>Proves the deeper end-state: instead of a side-car index plus an ambient context, object
 * <em>existence</em> becomes a function of the <b>active variant</b> — the
 * same mechanism IIDM already uses for per-variant <em>state</em> (tap position, switch open, terminal
 * p/q). One network answers {@code getLine(id)} differently per working variant; the "branch" is just a
 * cloned variant, and the working variant <em>is</em> the context.</p>
 *
 * <p>This is a {@link MultiVariantObject}: its per-variant hidden-id sets are grown and copied by the
 * <em>real</em> variant lifecycle ({@code VariantManagerImpl.cloneVariant} drives
 * {@code extendVariantArraySize} / {@code allocateVariantArrayElement}), so cloning a variant clones
 * existence exactly as it clones state — the network-store {@code fullVariantNum} property, in-place.
 * Registered on a network only when structural branching is used; absent (null) for every normal
 * network, so the index hot path is unchanged.</p>
 *
 * @author Claude
 */
final class VariantScopedExistence implements MultiVariantObject {

    private final VariantManagerHolder holder;
    // Per variant index, the ids hidden (tombstoned) in that variant. An id absent from the set exists in
    // that variant; the vast majority of ids are in no set at all, so resolution is a single set lookup.
    private final Map<Integer, Set<String>> hiddenByVariant = new HashMap<>();
    private int variantArraySize;
    private boolean anyHidden;

    VariantScopedExistence(VariantManagerHolder holder, int variantArraySize) {
        this.holder = holder;
        this.variantArraySize = variantArraySize;
        for (int i = 0; i < variantArraySize; i++) {
            hiddenByVariant.put(i, new HashSet<>());
        }
    }

    /** Whether the working variant is a structural one (so a newly-added object should exist only in it). */
    boolean isCurrentVariantStructural() {
        return holder.getVariantManager().isCurrentVariantStructural();
    }

    /** True if {@code id} is hidden (does not exist) in the network's current working variant. */
    boolean isHidden(String id) {
        if (!anyHidden) {
            return false;
        }
        Set<String> hidden = hiddenByVariant.get(holder.getVariantIndex());
        return hidden != null && hidden.contains(id);
    }

    /** True if any id is hidden in any variant — lets the index skip filtering entirely when nothing is. */
    boolean anyHidden() {
        return anyHidden;
    }

    /** Hide {@code id} in the current working variant only (a variant-scoped tombstone). */
    void hideInCurrentVariant(String id) {
        hiddenByVariant.computeIfAbsent(holder.getVariantIndex(), k -> new HashSet<>()).add(id);
        anyHidden = true;
    }

    /**
     * Make {@code id} exist <em>only</em> in the current working variant: hide it in every other variant.
     * Used for a branch-owned object (it is structurally present in the index, but should surface only in
     * the branch's variant). Later clones inherit the right visibility via the variant-array copy.
     */
    void existOnlyInCurrentVariant(String id) {
        int current = holder.getVariantIndex();
        for (Map.Entry<Integer, Set<String>> entry : hiddenByVariant.entrySet()) {
            if (entry.getKey() != current) {
                entry.getValue().add(id);
            }
        }
        anyHidden = true;
    }

    /** Show {@code id} again in the current working variant. */
    void showInCurrentVariant(String id) {
        Set<String> hidden = hiddenByVariant.get(holder.getVariantIndex());
        if (hidden != null) {
            hidden.remove(id);
        }
        recomputeAnyHidden();
    }

    private void recomputeAnyHidden() {
        anyHidden = hiddenByVariant.values().stream().anyMatch(s -> !s.isEmpty());
    }

    // --- MultiVariantObject: existence rides the real variant lifecycle, exactly like state ---

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        Set<String> source = hiddenByVariant.getOrDefault(sourceIndex, Set.of());
        for (int i = 0; i < number; i++) {
            hiddenByVariant.put(initVariantArraySize + i, new HashSet<>(source));
        }
        variantArraySize = initVariantArraySize + number;
        recomputeAnyHidden();
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        Set<String> source = hiddenByVariant.getOrDefault(sourceIndex, Set.of());
        for (int index : indexes) {
            hiddenByVariant.put(index, new HashSet<>(source));
        }
        recomputeAnyHidden();
    }

    @Override
    public void reduceVariantArraySize(int number) {
        for (int i = 0; i < number; i++) {
            hiddenByVariant.remove(--variantArraySize);
        }
        recomputeAnyHidden();
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        Set<String> hidden = hiddenByVariant.get(index);
        if (hidden != null) {
            hidden.clear();
        }
        recomputeAnyHidden();
    }
}
