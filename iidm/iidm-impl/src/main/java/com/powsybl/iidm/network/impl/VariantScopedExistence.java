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
 * <p>Object <em>existence</em> is a function of the <b>active variant</b>, tracked <b>per object</b> (by
 * identity) so two different objects may share an id across sibling variants; the {@link NetworkIndex}
 * resolves an id to the single object visible in the active variant.</p>
 *
 * <p>Existence is stored <b>copy-on-write</b> on the shared {@link VariantCowState}, exactly like the
 * columnar state stores. A variant records only the objects whose visibility it <em>diverges</em> from its
 * parent: {@code present} (shown here) and {@code absent} (hidden here). A structural ({@code STRUCTURAL})
 * clone copies nothing (O(1)); a read resolves through the clone parentage until it hits an explicit entry
 * or a <em>dense</em> variant (the initial variant or a {@code STATE_ONLY} clone, which own their full view).
 * A write to a variant first <b>freezes</b> the touched object into the copy-on-write children still
 * inheriting it, so a later change to a parent never leaks into a variant forked earlier. Eager
 * ({@code STATE_ONLY}) clones materialise the source's resolved view, so they are dense and independent.</p>
 *
 * <p>This is a {@link MultiVariantObject}: it rides the real variant lifecycle. Registered on a network only
 * when structural branching is used; absent (null) for every normal network, so the index hot path is
 * unchanged.</p>
 *
 * @author Claude
 */
final class VariantScopedExistence implements MultiVariantObject {

    private final VariantManagerHolder holder;
    private final VariantCowState cowState;
    // Per variant, the objects it diverges from its parent: present (shown) / absent (hidden). A variant with
    // no entry for an object inherits it through the parentage (or, at a dense variant, the base default).
    private final Map<Integer, Set<Identifiable<?>>> presentByVariant = new HashMap<>();
    private final Map<Integer, Set<Identifiable<?>>> absentByVariant = new HashMap<>();
    // Objects added in a structural variant — their base default is "does not exist" (visible only where an
    // ancestor makes them present); every other object is a base object, visible unless made absent.
    private final Set<Identifiable<?>> everAdded = identitySet();
    // Every object that has any explicit entry anywhere — for eager materialisation and cleanup.
    private final Set<Identifiable<?>> everScoped = identitySet();
    private int variantArraySize;
    private boolean anyScoped;

    VariantScopedExistence(VariantManagerHolder holder, int variantArraySize, VariantCowState cowState) {
        this.holder = holder;
        this.cowState = cowState;
        this.variantArraySize = variantArraySize;
    }

    private static Set<Identifiable<?>> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private Set<Identifiable<?>> present(int variant) {
        return presentByVariant.computeIfAbsent(variant, k -> identitySet());
    }

    private Set<Identifiable<?>> absent(int variant) {
        return absentByVariant.computeIfAbsent(variant, k -> identitySet());
    }

    private boolean hasExplicitEntry(int variant, Identifiable<?> obj) {
        Set<Identifiable<?>> p = presentByVariant.get(variant);
        if (p != null && p.contains(obj)) {
            return true;
        }
        Set<Identifiable<?>> a = absentByVariant.get(variant);
        return a != null && a.contains(obj);
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
        return isVisibleInVariant(obj, holder.getVariantIndex());
    }

    // Resolve visibility of obj as seen from variant v: walk the clone parentage while the variant is
    // copy-on-write, stopping at the first explicit entry or at a dense variant (which owns its full view).
    private boolean isVisibleInVariant(Identifiable<?> obj, int v) {
        int variant = v;
        while (true) {
            Set<Identifiable<?>> a = absentByVariant.get(variant);
            if (a != null && a.contains(obj)) {
                return false;
            }
            Set<Identifiable<?>> p = presentByVariant.get(variant);
            if (p != null && p.contains(obj)) {
                return true;
            }
            if (!cowState.isCow(variant)) {
                return !everAdded.contains(obj); // dense variant: base default
            }
            variant = cowState.getParent(variant);
            if (variant == VariantCowState.NO_PARENT) {
                return !everAdded.contains(obj);
            }
        }
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
        freezeInheritors(current, obj); // obj is currently visible here; children keep seeing it
        absent(current).add(obj);
        Set<Identifiable<?>> p = presentByVariant.get(current);
        if (p != null) {
            p.remove(obj);
        }
        everScoped.add(obj);
        anyScoped = true;
    }

    /**
     * Make {@code obj} exist <em>only</em> in the current working variant (and, by parentage, variants later
     * forked from it). Marking it added first makes its base default "absent", so the freeze correctly hides
     * it from the variants forked before this add.
     */
    void existOnlyInCurrentVariant(Identifiable<?> obj) {
        int current = holder.getVariantIndex();
        everAdded.add(obj);
        freezeInheritors(current, obj); // obj now resolves as absent; children forked earlier stay without it
        present(current).add(obj);
        everScoped.add(obj);
        anyScoped = true;
    }

    /** Show {@code obj} again in the current working variant (undo a variant-scoped tombstone). */
    void showInCurrentVariant(Identifiable<?> obj) {
        int current = holder.getVariantIndex();
        freezeInheritors(current, obj);
        Set<Identifiable<?>> a = absentByVariant.get(current);
        if (a != null) {
            a.remove(obj);
        }
        if (!isVisibleInVariant(obj, current)) {
            present(current).add(obj); // override an inherited absent
        }
        recomputeAnyScoped();
    }

    // Before variant p's entry for obj changes, freeze p's current resolved visibility of obj into every
    // copy-on-write child still inheriting it, so the change does not leak into a variant forked earlier.
    private void freezeInheritors(int p, Identifiable<?> obj) {
        int[] children = cowState.getCowChildren(p);
        if (children.length == 0) {
            return;
        }
        boolean visibleInParent = isVisibleInVariant(obj, p);
        for (int c : children) {
            if (!hasExplicitEntry(c, obj)) {
                if (visibleInParent) {
                    present(c).add(obj);
                } else {
                    absent(c).add(obj);
                }
            }
        }
    }

    /**
     * Freeze into its copy-on-write children everything {@code variant} diverges, before it is removed or
     * overwritten (its children are about to be re-parented onto its own parent). Mirrors
     * {@link VariantColumnStore#materializeInheritors(int)}.
     */
    void materializeInheritors(int variant) {
        int[] children = cowState.getCowChildren(variant);
        if (children.length == 0) {
            return;
        }
        Set<Identifiable<?>> diverged = identitySet();
        Set<Identifiable<?>> p = presentByVariant.get(variant);
        if (p != null) {
            diverged.addAll(p);
        }
        Set<Identifiable<?>> a = absentByVariant.get(variant);
        if (a != null) {
            diverged.addAll(a);
        }
        for (Identifiable<?> obj : diverged) {
            boolean visible = isVisibleInVariant(obj, variant);
            for (int c : children) {
                if (!hasExplicitEntry(c, obj)) {
                    if (visible) {
                        present(c).add(obj);
                    } else {
                        absent(c).add(obj);
                    }
                }
            }
        }
    }

    /**
     * Forget a removed variant. Its copy-on-write children were materialised before this call
     * (see {@code NetworkImpl#materializeCowInheritorsOf}), so just drop its own divergence.
     */
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

    // Eagerly materialise, into dense variant {@code target}, the resolved view of {@code source} — every
    // scoped object whose visibility from source differs from the base default. Used for STATE_ONLY clones,
    // which are dense (resolution stops at them) and therefore independent of later parent writes.
    private void materializeResolvedInto(int target, int source) {
        for (Identifiable<?> obj : everScoped) {
            boolean visible = isVisibleInVariant(obj, source);
            boolean base = !everAdded.contains(obj);
            if (visible != base) {
                if (visible) {
                    present(target).add(obj);
                } else {
                    absent(target).add(obj);
                }
            }
        }
    }

    // --- MultiVariantObject: existence rides the real variant lifecycle, exactly like state ---

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        extendVariantArraySize(initVariantArraySize, number, sourceIndex, false);
    }

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex, boolean structuralClone) {
        if (!structuralClone) {
            for (int i = 0; i < number; i++) {
                materializeResolvedInto(initVariantArraySize + i, sourceIndex);
            }
        }
        // structural clone copies nothing — the new variants inherit through the parentage
        variantArraySize = initVariantArraySize + number;
        recomputeAnyScoped();
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        allocateVariantArrayElement(indexes, sourceIndex, false);
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex, boolean structuralClone) {
        for (int index : indexes) {
            // a recycled index must start clean before it inherits or is materialised
            presentByVariant.remove(index);
            absentByVariant.remove(index);
            if (!structuralClone) {
                materializeResolvedInto(index, sourceIndex);
            }
        }
        recomputeAnyScoped();
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
