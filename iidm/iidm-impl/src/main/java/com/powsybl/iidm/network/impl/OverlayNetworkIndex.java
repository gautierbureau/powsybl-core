/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Identifiable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p><b>Spike — structural variant / copy-on-write network branching.</b> See
 * {@code structural-variant-spike.md}.</p>
 *
 * <p>A {@link NetworkIndex} subclass that acts as a read-mostly <em>overlay</em> over a shared,
 * read-only base index. Because it <em>is</em> a {@code NetworkIndex}, a {@link NetworkImpl} can hold
 * one in place of a plain index (see {@link NetworkImpl#createStructuralBranch}) and all of its
 * {@code index.xxx} call sites work unchanged — the branch becomes a traversable {@code Network}.</p>
 *
 * <p>A structural branch shares the parent's whole object registry by reference and records only a
 * small <em>delta</em> on top: <em>additions</em> (objects that exist only in the branch, e.g. the
 * fictitious voltage level and the two half-lines when a line is split at a fault point) and
 * <em>tombstones</em> (ids of base objects hidden in the branch, e.g. the original line). Every read
 * merges the delta over the base; the inherited base-class storage is left unused, and the shared base
 * index is never mutated — so the parent network and any sibling branch are unaffected. Memory is
 * O(delta), not O(network).</p>
 *
 * @author Claude
 */
class OverlayNetworkIndex extends NetworkIndex {

    private final NetworkIndex base;

    private final Map<String, Identifiable<?>> addedById = new HashMap<>();
    private final Map<String, String> addedIdByAlias = new HashMap<>();
    private final Map<Class<? extends Identifiable>, Set<Identifiable<?>>> addedByClass = new HashMap<>();
    // Ids (canonical, alias-resolved) of base objects hidden in this branch.
    private final Set<String> tombstoned = new HashSet<>();

    // When set, checkAndAdd lets a branch-owned object shadow a same-id base object (copy-on-write
    // materialisation of the dirty region), instead of rejecting it as a duplicate.
    private boolean materializing;

    OverlayNetworkIndex(NetworkIndex base) {
        this.base = base;
    }

    /**
     * Reconstruct part of the base into this branch as branch-owned copies: while this is set, the
     * branch's own adders may recreate objects that carry base ids, shadowing the base. Use around the
     * materialisation of the dirty region (the objects the write path is about to mutate).
     */
    void beginMaterialize() {
        materializing = true;
    }

    void endMaterialize() {
        materializing = false;
    }

    private String resolveAlias(String idOrAlias) {
        String viaAdded = addedIdByAlias.get(idOrAlias);
        if (viaAdded != null) {
            return viaAdded;
        }
        Identifiable<?> baseObj = base.get(idOrAlias);
        return baseObj != null ? baseObj.getId() : idOrAlias;
    }

    // An id is shadowed when the branch overrides the base's view of it: either a branch-local object
    // carries that id (a new object or a copy-on-write replacement) or it has been tombstoned.
    private boolean shadowed(String id) {
        return addedById.containsKey(id) || tombstoned.contains(id);
    }

    @Override
    Identifiable get(String idOrAlias) {
        NetworkIndex.checkId(idOrAlias);
        String id = resolveAlias(idOrAlias);
        Identifiable<?> added = addedById.get(id);
        if (added != null) {
            // a branch-local object (new, or a copy-on-write replacement) shadows the base
            return added;
        }
        if (tombstoned.contains(id)) {
            return null;
        }
        return base.get(id);
    }

    @Override
    <T extends Identifiable> T get(String id, Class<T> clazz) {
        Identifiable<?> obj = get(id);
        if (obj != null && clazz.isAssignableFrom(obj.getClass())) {
            return (T) obj;
        }
        return null;
    }

    @Override
    boolean contains(String idOrAlias) {
        if (materializing) {
            // During materialisation a base-only id is "free" so the branch's own adders can rebuild it
            // (it will shadow the base in checkAndAdd). Only an already branch-local id is taken.
            return addedById.containsKey(resolveAlias(idOrAlias));
        }
        return get(idOrAlias) != null;
    }

    @Override
    <T extends Identifiable> Set<T> getAll(Class<T> clazz) {
        Set<? extends Identifiable> baseAll = base.getAll(clazz);
        Set<Identifiable<?>> added = addedByClass.get(clazz);
        if (tombstoned.isEmpty() && (added == null || added.isEmpty())) {
            return (Set<T>) baseAll;
        }
        Set<Identifiable<?>> merged = new LinkedHashSet<>(baseAll.size() + (added == null ? 0 : added.size()));
        for (Identifiable<?> obj : baseAll) {
            if (!shadowed(obj.getId())) {
                merged.add(obj);
            }
        }
        if (added != null) {
            merged.addAll(added);
        }
        return (Set<T>) merged;
    }

    @Override
    Collection<Identifiable<?>> getAll() {
        if (tombstoned.isEmpty() && addedById.isEmpty()) {
            return base.getAll();
        }
        Set<Identifiable<?>> merged = new LinkedHashSet<>();
        for (Identifiable<?> obj : base.getAll()) {
            if (!shadowed(obj.getId())) {
                merged.add(obj);
            }
        }
        merged.addAll(addedById.values());
        return Collections.unmodifiableCollection(merged);
    }

    @Override
    List<MultiVariantObject> getStatefulObjects() {
        List<MultiVariantObject> stateful = new ArrayList<>();
        for (MultiVariantObject obj : base.getStatefulObjects()) {
            if (obj instanceof Identifiable<?> identifiable && !shadowed(identifiable.getId())) {
                stateful.add(obj);
            }
        }
        for (Identifiable<?> obj : addedById.values()) {
            if (obj instanceof MultiVariantObject multiVariantObject) {
                stateful.add(multiVariantObject);
            }
        }
        return stateful;
    }

    @Override
    void checkAndAdd(Identifiable<?> obj) {
        NetworkIndex.checkId(obj.getId());
        String id = obj.getId();
        if (get(id) != null) {
            // In materialize mode a branch-owned object may reconstruct (shadow) a base object with the
            // same id — copy-on-write of the dirty region, so the branch's own adders rebuild the objects
            // the write path is about to mutate. Outside that mode a visible id is a genuine duplicate.
            boolean shadowingBase = materializing && !addedById.containsKey(id) && base.get(id) != null;
            if (!shadowingBase) {
                throw new PowsyblException("Object (" + obj.getClass().getName() + ") '" + id + "' already exists");
            }
        }
        // Re-adding a previously tombstoned id resurrects it as a branch-local object.
        tombstoned.remove(id);
        addedById.put(id, obj);
        obj.getAliases().forEach(alias -> addAlias(obj, alias));
        addedByClass.computeIfAbsent(obj.getClass(), k -> new LinkedHashSet<>()).add(obj);
    }

    @Override
    boolean addAlias(Identifiable<?> obj, String alias) {
        if (!materializing && get(alias) != null) {
            throw new PowsyblException(String.format("Object (%s) with alias '%s' cannot be created because alias already exists",
                    obj.getClass(), alias));
        }
        addedIdByAlias.put(alias, obj.getId());
        return true;
    }

    @Override
    public <I extends Identifiable<I>> void removeAlias(Identifiable<?> obj, String alias) {
        addedIdByAlias.remove(alias);
    }

    @Override
    void remove(Identifiable obj) {
        NetworkIndex.checkId(obj.getId());
        String id = obj.getId();
        Identifiable<?> added = addedById.remove(id);
        if (added != null) {
            added.getAliases().forEach(addedIdByAlias::remove);
            Set<Identifiable<?>> byClass = addedByClass.get(added.getClass());
            if (byClass != null) {
                byClass.remove(added);
            }
            // If this branch-local object was shadowing a base object with the same id (a copy-on-write
            // materialisation), removing it must leave the base object hidden — otherwise it would
            // reappear in the branch. Leave a tombstone.
            if (base.get(id) != null) {
                tombstoned.add(id);
            }
            return;
        }
        if (base.get(id) == null) {
            throw new PowsyblException("Object (" + obj.getClass().getName() + ") '" + id + "' not found");
        }
        tombstoned.add(id);
    }

    /**
     * Copy-on-write replacement: shadow a base object with a branch-local copy carrying the same id.
     * This is the write-path primitive — materialising a base object into the branch so it can be
     * mutated there without touching the shared base. Reads for {@code baseObj.getId()} now resolve to
     * {@code branchCopy} in this branch only.
     */
    void replace(Identifiable<?> baseObj, Identifiable<?> branchCopy) {
        String id = baseObj.getId();
        if (!id.equals(branchCopy.getId())) {
            throw new PowsyblException("Copy-on-write replacement must keep the same id: '" + id
                    + "' vs '" + branchCopy.getId() + "'");
        }
        if (base.get(id) == null) {
            throw new PowsyblException("Object '" + id + "' is not in the base, nothing to replace");
        }
        if (addedById.containsKey(id)) {
            throw new PowsyblException("Object '" + id + "' has already been replaced in this branch");
        }
        tombstoned.remove(id);
        addedById.put(id, branchCopy);
        branchCopy.getAliases().forEach(alias -> addedIdByAlias.put(alias, id));
        addedByClass.computeIfAbsent(branchCopy.getClass(), k -> new LinkedHashSet<>()).add(branchCopy);
    }

    // --- spike-friendly aliases used by the index-level tests ---

    void add(Identifiable<?> obj) {
        checkAndAdd(obj);
    }

    void tombstone(Identifiable<?> obj) {
        remove(obj);
    }

    /** Number of delta entries (additions + tombstones) — the branch's structural footprint. */
    int deltaSize() {
        return addedById.size() + tombstoned.size();
    }

    boolean isPristine() {
        return addedById.isEmpty() && tombstoned.isEmpty();
    }

    NetworkIndex getBase() {
        return base;
    }
}
