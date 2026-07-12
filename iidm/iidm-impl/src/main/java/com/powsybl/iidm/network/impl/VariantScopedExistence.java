/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Identifiable;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <p><b>Variant-scoped existence.</b> See {@code structural-variant-public-api.md}.</p>
 *
 * <p>Object <em>existence</em> is a function of the <b>active variant</b> — the same mechanism IIDM already
 * uses for per-variant <em>state</em>. A structural variant is just a cloned variant, and the working variant
 * <em>is</em> the context.</p>
 *
 * <p>Visibility is tracked <b>per object</b> (by identity), not per id, so two <em>different</em> objects may
 * share an id across sibling variants — an id added independently in two variants, or an id removed then
 * re-added in one variant. The {@link NetworkIndex} resolves an id to the single object visible in the active
 * variant. Both deltas are per-variant and <b>snapshot-copied at clone</b>, exactly like state and
 * {@link VariantScopedMembership}: {@code hiddenByVariant} (a removal tombstone) and {@code addedByVariant}
 * (an object added in a structural variant). Copying both at clone gives snapshot semantics — a later add or
 * removal in a parent variant does not leak into a variant forked earlier. {@code everAdded} is the set of
 * every object ever added in a structural variant, so a read can tell an added object (visible only where the
 * active variant lists it) from a normal base object (visible unless tombstoned).</p>
 *
 * <p>This is a {@link MultiVariantObject}: its per-variant sets are grown and copied by the real variant
 * lifecycle, so cloning a variant clones existence exactly as it clones state. Registered on a network only
 * when structural branching is used; absent (null) for every normal network, so the index hot path is
 * unchanged.</p>
 *
 * @author Claude
 */
final class VariantScopedExistence implements MultiVariantObject {

    private final VariantManagerHolder holder;
    // Per variant index, the objects hidden (tombstoned) in that variant — a removal in a structural variant.
    // Snapshot-copied at clone.
    private final Map<Integer, Set<Identifiable<?>>> hiddenByVariant = new HashMap<>();
    // Per variant index, the objects added in a structural variant and visible in that variant. Snapshot-copied
    // at clone (like hiddenByVariant and membership), so an add to a parent after a fork does not leak into the
    // fork.
    private final Map<Integer, Set<Identifiable<?>>> addedByVariant = new HashMap<>();
    // Every object ever added in a structural variant, to tell an added object (visible only where addedByVariant
    // lists it) from a normal base object (visible unless tombstoned).
    private final Set<Identifiable<?>> everAdded = Collections.newSetFromMap(new IdentityHashMap<>());
    private int variantArraySize;
    // True while any object is hidden or added anywhere — lets the index skip filtering entirely when nothing is.
    private boolean anyScoped;

    VariantScopedExistence(VariantManagerHolder holder, int variantArraySize) {
        this.holder = holder;
        this.variantArraySize = variantArraySize;
        for (int i = 0; i < variantArraySize; i++) {
            hiddenByVariant.put(i, identitySet());
            addedByVariant.put(i, identitySet());
        }
    }

    private static Set<Identifiable<?>> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static Set<Identifiable<?>> identitySet(Set<Identifiable<?>> source) {
        Set<Identifiable<?>> copy = identitySet();
        copy.addAll(source);
        return copy;
    }

    /** Whether the working variant is a structural one (so a newly-added object should exist only in it). */
    boolean isCurrentVariantStructural() {
        return holder.getVariantManager().isCurrentVariantStructural();
    }

    /** True if {@code obj} is visible (exists) in the network's current working variant. */
    boolean isVisible(Identifiable<?> obj) {
        if (!anyScoped) {
            return true;
        }
        int current = holder.getVariantIndex();
        Set<Identifiable<?>> hidden = hiddenByVariant.get(current);
        if (hidden != null && hidden.contains(obj)) {
            return false; // removed (tombstoned) in this variant
        }
        if (everAdded.contains(obj)) {
            // an object added in a structural variant exists only where the active variant lists it
            Set<Identifiable<?>> added = addedByVariant.get(current);
            return added != null && added.contains(obj);
        }
        return true;
    }

    /** True if any object is hidden or added in any variant — lets the index skip filtering when nothing is. */
    boolean anyScoped() {
        return anyScoped;
    }

    /** Whether {@code obj} is an object added in a structural variant (as opposed to a base/shared object). */
    boolean isAddedObject(Identifiable<?> obj) {
        return everAdded.contains(obj);
    }

    /** Hide {@code obj} in the current working variant only (a variant-scoped tombstone). */
    void hideInCurrentVariant(Identifiable<?> obj) {
        hiddenByVariant.computeIfAbsent(holder.getVariantIndex(), k -> identitySet()).add(obj);
        anyScoped = true;
    }

    /**
     * Make {@code obj} exist <em>only</em> in the current working variant (and, by snapshot copy at clone,
     * variants later forked from it). Siblings and variants forked before the add do not see it.
     */
    void existOnlyInCurrentVariant(Identifiable<?> obj) {
        everAdded.add(obj);
        addedByVariant.computeIfAbsent(holder.getVariantIndex(), k -> identitySet()).add(obj);
        anyScoped = true;
    }

    /** Show {@code obj} again in the current working variant (undo a variant-scoped tombstone). */
    void showInCurrentVariant(Identifiable<?> obj) {
        Set<Identifiable<?>> hidden = hiddenByVariant.get(holder.getVariantIndex());
        if (hidden != null) {
            hidden.remove(obj);
        }
        recomputeAnyScoped();
    }

    /**
     * Forget a removed variant. Variants forked from it already hold their own snapshot copies (taken at their
     * clone), so nothing needs materialising here — just drop this variant's sets.
     */
    void forgetVariant(int index) {
        hiddenByVariant.remove(index);
        addedByVariant.remove(index);
        recomputeAnyScoped();
    }

    /**
     * Forget an object physically removed from the index, so its per-variant entries do not retain a dead
     * reference. (A variant-scoped tombstone keeps the object in the index; this is for a real removal.)
     */
    void forgetObject(Identifiable<?> obj) {
        everAdded.remove(obj);
        hiddenByVariant.values().forEach(s -> s.remove(obj));
        addedByVariant.values().forEach(s -> s.remove(obj));
        recomputeAnyScoped();
    }

    private void recomputeAnyScoped() {
        anyScoped = !everAdded.isEmpty()
                || hiddenByVariant.values().stream().anyMatch(s -> !s.isEmpty());
    }

    // --- MultiVariantObject: existence rides the real variant lifecycle, exactly like state ---

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        Set<Identifiable<?>> hiddenSource = hiddenByVariant.getOrDefault(sourceIndex, Set.of());
        Set<Identifiable<?>> addedSource = addedByVariant.getOrDefault(sourceIndex, Set.of());
        for (int i = 0; i < number; i++) {
            hiddenByVariant.put(initVariantArraySize + i, identitySet(hiddenSource));
            addedByVariant.put(initVariantArraySize + i, identitySet(addedSource));
        }
        variantArraySize = initVariantArraySize + number;
        recomputeAnyScoped();
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        Set<Identifiable<?>> hiddenSource = hiddenByVariant.getOrDefault(sourceIndex, Set.of());
        Set<Identifiable<?>> addedSource = addedByVariant.getOrDefault(sourceIndex, Set.of());
        for (int index : indexes) {
            hiddenByVariant.put(index, identitySet(hiddenSource));
            addedByVariant.put(index, identitySet(addedSource));
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
        Set<Identifiable<?>> hidden = hiddenByVariant.get(index);
        if (hidden != null) {
            hidden.clear();
        }
        Set<Identifiable<?>> added = addedByVariant.get(index);
        if (added != null) {
            added.clear();
        }
        recomputeAnyScoped();
    }
}
