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
 * <p><b>Variant-scoped existence.</b> See {@code structural-variant-public-api.md}.</p>
 *
 * <p>Object <em>existence</em> is a function of the <b>active variant</b> — the same mechanism IIDM
 * already uses for per-variant <em>state</em> (tap position, switch open, terminal p/q). One network
 * answers {@code getLine(id)} differently per working variant; a structural variant is just a cloned
 * variant, and the working variant <em>is</em> the context.</p>
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

    private static final int DEAD_VARIANT = -2;

    private final VariantManagerHolder holder;
    // Per variant index, the ids hidden (tombstoned) in that variant — a removal in a structural variant.
    // Copied at clone (small, snapshot-correct). An id absent from the set exists in that variant.
    private final Map<Integer, Set<String>> hiddenByVariant = new HashMap<>();
    // Objects ADDED in a structural variant. id -> the variant it was added in;
    // the object is visible in that variant and its descendants only. Global (not copied per variant), so
    // an add is O(1) instead of O(variants); visibility resolves through the variant parentage.
    private final Map<String, Integer> existsOnlyIn = new HashMap<>();
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
        int current = holder.getVariantIndex();
        // removed in this variant (materialised per variant, copied at clone)
        Set<String> hidden = hiddenByVariant.get(current);
        if (hidden != null && hidden.contains(id)) {
            return true;
        }
        // added in a structural variant: visible only in that variant and its descendants
        Integer addedIn = existsOnlyIn.get(id);
        return addedIn != null && !isDescendantOrSelf(current, addedIn);
    }

    /** Walk the variant parentage from {@code variant} up to the roots, looking for {@code ancestor}. */
    private boolean isDescendantOrSelf(int variant, int ancestor) {
        int v = variant;
        while (v != -1) {
            if (v == ancestor) {
                return true;
            }
            v = holder.getVariantManager().parentVariant(v);
        }
        return false;
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
     * Make {@code id} exist <em>only</em> in the current working variant (and its descendants). O(1): one
     * map entry, resolved through the variant parentage at read time — the copy-on-write model. Later
     * clones of this variant inherit visibility by parentage; siblings and ancestors do not see it.
     */
    void existOnlyInCurrentVariant(String id) {
        existsOnlyIn.put(id, holder.getVariantIndex());
        anyHidden = true;
    }

    /**
     * Forget a removed variant: any object that existed only in it becomes invisible everywhere (its index
     * may be recycled, so the mapping must not linger), and its removal set is dropped.
     */
    void forgetVariant(int index) {
        existsOnlyIn.replaceAll((id, addedIn) -> addedIn == index ? DEAD_VARIANT : addedIn);
        hiddenByVariant.remove(index);
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
        anyHidden = !existsOnlyIn.isEmpty() || hiddenByVariant.values().stream().anyMatch(s -> !s.isEmpty());
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
