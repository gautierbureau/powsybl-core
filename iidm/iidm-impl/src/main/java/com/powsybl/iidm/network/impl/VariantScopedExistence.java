/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Identifiable;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p><b>Variant-scoped existence.</b></p>
 *
 * <p>Object <em>existence</em> is a function of the <b>active variant</b>, tracked <b>per object</b> (by
 * identity) so two different objects may share an id across sibling variants; the {@link NetworkIndex}
 * resolves an id to the single object visible in the active variant.</p>
 *
 * <p>Existence is stored <b>copy-on-write</b> on the shared {@link VariantCowState}, exactly like the
 * columnar state stores. A variant records only the objects whose visibility it <em>diverges</em> from its
 * parent: {@code present} (shown here) and {@code absent} (hidden here). A clone copies nothing (O(1)); a
 * read resolves through the clone parentage until it hits an explicit entry or the <em>dense</em> initial
 * variant, which owns the base view.</p>
 *
 * <p>The delta is <b>live</b>, not a snapshot — the same contract as a network-store partial variant
 * resolved against its full variant. A structural change made in the initial variant is therefore seen by
 * every variant that has not recorded its own entry for that object, which is how {@code iidm-impl} has
 * always behaved for structure. Per-variant <em>state</em> is different: it is snapshotted on clone
 * (freeze-on-write in the columnar stores), because a variant has always owned its own state values.</p>
 *
 * <p>This is a {@link MultiVariantObject}: it rides the real variant lifecycle. Registered on a network on
 * its first clone; absent (null) while a network has a single variant, so the index hot path is unchanged
 * for networks that never fork.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
final class VariantScopedExistence implements MultiVariantObject {

    /** The shared base view: network-store's full variant. */
    private static final int BASE_VARIANT = 0;

    private final VariantManagerHolder holder;
    // Per variant, the objects it diverges from its parent: present (shown) / absent (hidden). A variant with
    // no entry for an object inherits it through the parentage (or, at a dense variant, the base default).
    //
    // Thread-safety: a variant is edited by at most one thread (the multi-thread contract is one thread per
    // variant), so the per-variant sets are thread-confined and stay plain. The maps keyed by variant, and
    // the sets spanning every variant below, are shared between those threads and are concurrent.
    private final Map<Integer, Set<Identifiable<?>>> presentByVariant = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Identifiable<?>>> absentByVariant = new ConcurrentHashMap<>();
    // Objects added in a variant — their base default is "does not exist" (visible only where an ancestor
    // makes them present); every other object is a base object, visible unless made absent.
    private final Set<Identifiable<?>> everAdded = concurrentIdentitySet();
    // Every object that has any explicit entry anywhere — for cleanup and the anyScoped gate.
    private final Set<Identifiable<?>> everScoped = concurrentIdentitySet();
    private int variantArraySize;
    private volatile boolean anyScoped;

    VariantScopedExistence(VariantManagerHolder holder, int variantArraySize) {
        this.holder = holder;
        this.variantArraySize = variantArraySize;
    }

    private static Set<Identifiable<?>> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    // Identifiables do not override equals/hashCode, so a plain concurrent set already compares by identity.
    private static Set<Identifiable<?>> concurrentIdentitySet() {
        return ConcurrentHashMap.newKeySet();
    }

    private Set<Identifiable<?>> present(int variant) {
        return presentByVariant.computeIfAbsent(variant, k -> identitySet());
    }

    private Set<Identifiable<?>> absent(int variant) {
        return absentByVariant.computeIfAbsent(variant, k -> identitySet());
    }

    /** Whether structure is variant-scoped (so a newly-added object should exist only in the working variant). */
    boolean isVariantScopedStructure() {
        return holder.getVariantManager().isVariantScopedStructure();
    }

    /**
     * The objects that were added in a variant and are no longer visible in any of {@code liveVariants} —
     * their only variant is gone. They must be dropped from the network index, otherwise they stay
     * invisible for ever while still holding their id, and that id can never be used again.
     */
    Set<Identifiable<?>> collectOrphans(Collection<Integer> liveVariants) {
        Set<Identifiable<?>> orphans = identitySet();
        for (Identifiable<?> obj : everAdded) {
            boolean visibleSomewhere = false;
            for (int v : liveVariants) {
                if (isVisibleInVariant(obj, v)) {
                    visibleSomewhere = true;
                    break;
                }
            }
            if (!visibleSomewhere) {
                orphans.add(obj);
            }
        }
        return orphans;
    }

    /** True if {@code obj} is visible (exists) in the network's current working variant. */
    boolean isVisible(Identifiable<?> obj) {
        if (!anyScoped) {
            return true;
        }
        return isVisibleInVariant(obj, holder.getVariantIndex());
    }

    // Resolve visibility of obj as seen from variant v: the variant's own entry wins, otherwise the shared
    // base view answers. Two steps, never a walk -- a partial variant is a delta on the full variant, as in
    // network-store, so there is no inheritance chain to follow even when a variant was cloned from another
    // (the source's delta is copied into the clone, see copyDelta).
    private boolean isVisibleInVariant(Identifiable<?> obj, int v) {
        if (v != BASE_VARIANT) {
            Set<Identifiable<?>> a = absentByVariant.get(v);
            if (a != null && a.contains(obj)) {
                return false;
            }
            Set<Identifiable<?>> p = presentByVariant.get(v);
            if (p != null && p.contains(obj)) {
                return true;
            }
        }
        Set<Identifiable<?>> baseAbsent = absentByVariant.get(BASE_VARIANT);
        if (baseAbsent != null && baseAbsent.contains(obj)) {
            return false;
        }
        Set<Identifiable<?>> basePresent = presentByVariant.get(BASE_VARIANT);
        if (basePresent != null && basePresent.contains(obj)) {
            return true;
        }
        return !everAdded.contains(obj);
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
        int current = holder.getVariantIndex();
        absent(current).add(obj);
        Set<Identifiable<?>> p = presentByVariant.get(current);
        if (p != null) {
            p.remove(obj);
        }
        everScoped.add(obj);
        anyScoped = true;
    }

    /**
     * Make {@code obj} exist <em>only</em> in the current working variant (and, by parentage, the variants
     * that resolve through it). Marking it added makes its base default "absent", so it stays invisible in
     * the initial variant and in sibling variants. Only called while a cloned variant is the working one:
     * an object added in the initial variant is a base object, visible wherever it is not tombstoned.
     */
    void existOnlyInCurrentVariant(Identifiable<?> obj) {
        int current = holder.getVariantIndex();
        everAdded.add(obj);
        present(current).add(obj);
        everScoped.add(obj);
        anyScoped = true;
    }

    /** Show {@code obj} again in the current working variant (undo a variant-scoped tombstone). */
    void showInCurrentVariant(Identifiable<?> obj) {
        int current = holder.getVariantIndex();
        Set<Identifiable<?>> a = absentByVariant.get(current);
        if (a != null) {
            a.remove(obj);
        }
        if (!isVisibleInVariant(obj, current)) {
            present(current).add(obj); // override an inherited absent
        }
        recomputeAnyScoped();
    }

    /** Forget a removed variant: no other variant resolves through it, so just drop its own delta. */
    void forgetVariant(int index) {
        presentByVariant.remove(index);
        absentByVariant.remove(index);
        recomputeAnyScoped();
    }

    /**
     * Forget an object physically removed from the index, so its per-variant entries do not retain a dead
     * reference. (A variant-scoped tombstone keeps the object in the index; this is for a real removal.)
     */
    void forgetObject(Identifiable<?> obj) {
        everAdded.remove(obj);
        everScoped.remove(obj);
        presentByVariant.values().forEach(s -> s.remove(obj));
        absentByVariant.values().forEach(s -> s.remove(obj));
        recomputeAnyScoped();
    }

    private void recomputeAnyScoped() {
        // everScoped holds every object with any scoping (added or tombstoned) that is still in the index; an
        // added object whose only variant was removed lingers here and must keep resolving as absent, so the
        // gate cannot be driven off the (now empty) per-variant divergence alone.
        anyScoped = !everScoped.isEmpty();
    }

    // --- MultiVariantObject: existence rides the real variant lifecycle, exactly like state ---

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        for (int i = 0; i < number; i++) {
            copyDelta(sourceIndex, initVariantArraySize + i);
        }
        variantArraySize = initVariantArraySize + number;
        recomputeAnyScoped();
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        for (int index : indexes) {
            // a recycled index starts clean, then takes over the source's delta
            presentByVariant.remove(index);
            absentByVariant.remove(index);
            copyDelta(sourceIndex, index);
        }
        recomputeAnyScoped();
    }

    // Cloning from a variant that carries a delta copies that delta, so the clone is a delta on the base
    // like every other variant and is independent of what its source does next. Cloning from the base copies
    // nothing: the base view is shared and read live. Deltas hold only diverged objects, so this is cheap.
    private void copyDelta(int source, int target) {
        if (source == BASE_VARIANT || source == target) {
            return;
        }
        Set<Identifiable<?>> p = presentByVariant.get(source);
        if (p != null && !p.isEmpty()) {
            present(target).addAll(p);
        }
        Set<Identifiable<?>> a = absentByVariant.get(source);
        if (a != null && !a.isEmpty()) {
            absent(target).addAll(a);
        }
    }

    @Override
    public void reduceVariantArraySize(int number) {
        for (int i = 0; i < number; i++) {
            variantArraySize--;
            presentByVariant.remove(variantArraySize);
            absentByVariant.remove(variantArraySize);
        }
        recomputeAnyScoped();
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        Set<Identifiable<?>> p = presentByVariant.get(index);
        if (p != null) {
            p.clear();
        }
        Set<Identifiable<?>> a = absentByVariant.get(index);
        if (a != null) {
            a.clear();
        }
        recomputeAnyScoped();
    }
}
