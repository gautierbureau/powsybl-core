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
 * <p>Both deltas are per-variant and <b>snapshot-copied at clone</b>, exactly like state and
 * {@link VariantScopedMembership}: {@code hiddenByVariant} (a removal tombstone) and
 * {@code addedByVariant} (an object added in a structural variant). Copying both at clone gives snapshot
 * semantics — a later add or removal in a parent variant does <em>not</em> leak into a variant forked
 * earlier, and existence stays consistent with the terminal membership fold (which is snapshot-copied the
 * same way). {@code everAdded} is the global set of every id ever added in a structural variant, so a read
 * can tell an added object (visible only where the active variant lists it) from a normal base object
 * (visible unless tombstoned).</p>
 *
 * <p>This is a {@link MultiVariantObject}: its per-variant sets are grown and copied by the <em>real</em>
 * variant lifecycle ({@code VariantManagerImpl.cloneVariant} drives {@code extendVariantArraySize} /
 * {@code allocateVariantArrayElement}), so cloning a variant clones existence exactly as it clones state.
 * Registered on a network only when structural branching is used; absent (null) for every normal network,
 * so the index hot path is unchanged.</p>
 *
 * @author Claude
 */
final class VariantScopedExistence implements MultiVariantObject {

    private final VariantManagerHolder holder;
    // Per variant index, the ids hidden (tombstoned) in that variant — a removal in a structural variant.
    // Snapshot-copied at clone. An id absent from the set exists in that variant (unless it is an added
    // object not listed for that variant, see everAdded/addedByVariant).
    private final Map<Integer, Set<String>> hiddenByVariant = new HashMap<>();
    // Per variant index, the ids ADDED in a structural variant and visible in that variant. Snapshot-copied
    // at clone (like hiddenByVariant and membership), so an add to a parent after a fork does not leak into
    // the fork — the snapshot / copy-on-write model, consistent with the membership fold.
    private final Map<Integer, Set<String>> addedByVariant = new HashMap<>();
    // Every id ever added in a structural variant. Lets a read distinguish an added object (visible only
    // where addedByVariant lists it) from a normal base object (visible unless tombstoned).
    private final Set<String> everAdded = new HashSet<>();
    private int variantArraySize;
    // True while any id is hidden or added anywhere — lets the index skip filtering entirely when nothing is.
    private boolean anyScoped;

    VariantScopedExistence(VariantManagerHolder holder, int variantArraySize) {
        this.holder = holder;
        this.variantArraySize = variantArraySize;
        for (int i = 0; i < variantArraySize; i++) {
            hiddenByVariant.put(i, new HashSet<>());
            addedByVariant.put(i, new HashSet<>());
        }
    }

    /** Whether the working variant is a structural one (so a newly-added object should exist only in it). */
    boolean isCurrentVariantStructural() {
        return holder.getVariantManager().isCurrentVariantStructural();
    }

    /** True if {@code id} is hidden (does not exist) in the network's current working variant. */
    boolean isHidden(String id) {
        if (!anyScoped) {
            return false;
        }
        int current = holder.getVariantIndex();
        Set<String> hidden = hiddenByVariant.get(current);
        if (hidden != null && hidden.contains(id)) {
            return true; // removed (tombstoned) in this variant
        }
        if (everAdded.contains(id)) {
            // an object added in a structural variant exists only where the active variant lists it (its
            // add variant and the variants forked from it after the add, by snapshot copy)
            Set<String> added = addedByVariant.get(current);
            return added == null || !added.contains(id);
        }
        return false;
    }

    /** True if any id is hidden or added in any variant — lets the index skip filtering entirely when nothing is. */
    boolean anyHidden() {
        return anyScoped;
    }

    /** Whether {@code id} is an object added in a structural variant (as opposed to a base/shared object). */
    boolean isAddedObject(String id) {
        return everAdded.contains(id);
    }

    /** Hide {@code id} in the current working variant only (a variant-scoped tombstone). */
    void hideInCurrentVariant(String id) {
        hiddenByVariant.computeIfAbsent(holder.getVariantIndex(), k -> new HashSet<>()).add(id);
        anyScoped = true;
    }

    /**
     * Make {@code id} exist <em>only</em> in the current working variant (and, by snapshot copy at clone,
     * variants later forked from it). Recorded as per-variant state, so siblings and variants forked before
     * the add do not see it, and a later add does not leak into an earlier fork.
     */
    void existOnlyInCurrentVariant(String id) {
        everAdded.add(id);
        addedByVariant.computeIfAbsent(holder.getVariantIndex(), k -> new HashSet<>()).add(id);
        anyScoped = true;
    }

    /**
     * Forget a removed variant. Variants forked from it already hold their own snapshot copies of its
     * existence deltas (taken at their clone), so nothing needs materialising here — just drop this
     * variant's sets. Its index may be recycled; a later clone overwrites the sets.
     */
    void forgetVariant(int index) {
        hiddenByVariant.remove(index);
        addedByVariant.remove(index);
        recomputeAnyScoped();
    }

    /** Show {@code id} again in the current working variant. */
    void showInCurrentVariant(String id) {
        Set<String> hidden = hiddenByVariant.get(holder.getVariantIndex());
        if (hidden != null) {
            hidden.remove(id);
        }
        recomputeAnyScoped();
    }

    private void recomputeAnyScoped() {
        anyScoped = !everAdded.isEmpty()
                || hiddenByVariant.values().stream().anyMatch(s -> !s.isEmpty());
    }

    // --- MultiVariantObject: existence rides the real variant lifecycle, exactly like state ---

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        Set<String> hiddenSource = hiddenByVariant.getOrDefault(sourceIndex, Set.of());
        Set<String> addedSource = addedByVariant.getOrDefault(sourceIndex, Set.of());
        for (int i = 0; i < number; i++) {
            hiddenByVariant.put(initVariantArraySize + i, new HashSet<>(hiddenSource));
            addedByVariant.put(initVariantArraySize + i, new HashSet<>(addedSource));
        }
        variantArraySize = initVariantArraySize + number;
        recomputeAnyScoped();
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        Set<String> hiddenSource = hiddenByVariant.getOrDefault(sourceIndex, Set.of());
        Set<String> addedSource = addedByVariant.getOrDefault(sourceIndex, Set.of());
        for (int index : indexes) {
            hiddenByVariant.put(index, new HashSet<>(hiddenSource));
            addedByVariant.put(index, new HashSet<>(addedSource));
        }
        recomputeAnyScoped();
    }

    @Override
    public void reduceVariantArraySize(int number) {
        for (int i = 0; i < number; i++) {
            variantArraySize--;
            hiddenByVariant.remove(variantArraySize);
            addedByVariant.remove(variantArraySize);
        }
        recomputeAnyScoped();
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        Set<String> hidden = hiddenByVariant.get(index);
        if (hidden != null) {
            hidden.clear();
        }
        Set<String> added = addedByVariant.get(index);
        if (added != null) {
            added.clear();
        }
        recomputeAnyScoped();
    }
}
