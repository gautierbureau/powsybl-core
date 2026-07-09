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

    OverlayNetworkIndex(NetworkIndex base) {
        this.base = base;
    }

    private String resolveAlias(String idOrAlias) {
        String viaAdded = addedIdByAlias.get(idOrAlias);
        if (viaAdded != null) {
            return viaAdded;
        }
        Identifiable<?> baseObj = base.get(idOrAlias);
        return baseObj != null ? baseObj.getId() : idOrAlias;
    }

    @Override
    Identifiable get(String idOrAlias) {
        NetworkIndex.checkId(idOrAlias);
        String id = resolveAlias(idOrAlias);
        if (tombstoned.contains(id)) {
            return null;
        }
        Identifiable<?> added = addedById.get(id);
        if (added != null) {
            return added;
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
            if (!tombstoned.contains(obj.getId())) {
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
            if (!tombstoned.contains(obj.getId())) {
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
            if (obj instanceof Identifiable<?> identifiable && !tombstoned.contains(identifiable.getId())) {
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
            throw new PowsyblException("Object (" + obj.getClass().getName() + ") '" + id + "' already exists");
        }
        // Re-adding a previously tombstoned id resurrects it as a branch-local object.
        tombstoned.remove(id);
        addedById.put(id, obj);
        obj.getAliases().forEach(alias -> addAlias(obj, alias));
        addedByClass.computeIfAbsent(obj.getClass(), k -> new LinkedHashSet<>()).add(obj);
    }

    @Override
    boolean addAlias(Identifiable<?> obj, String alias) {
        if (get(alias) != null) {
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
            return;
        }
        if (base.get(id) == null) {
            throw new PowsyblException("Object (" + obj.getClass().getName() + ") '" + id + "' not found");
        }
        tombstoned.add(id);
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
