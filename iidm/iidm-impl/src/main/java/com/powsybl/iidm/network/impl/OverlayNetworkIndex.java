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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p><b>Spike — structural variant / copy-on-write network branching.</b> Not yet wired into
 * {@link NetworkImpl}; exercised only by {@code OverlayNetworkIndexTest}. See
 * {@code structural-variant-spike.md}.</p>
 *
 * <p>Read-mostly overlay over a shared, read-only base {@link NetworkIndex}. A structural branch of a
 * network shares its parent's whole object registry by reference and records only a small
 * <em>delta</em> on top:</p>
 * <ul>
 *   <li><b>additions</b> — objects that exist only in the branch (e.g. the fictitious voltage level
 *       and the two half-lines created when a line is split at a fault point), and</li>
 *   <li><b>tombstones</b> — ids of base objects hidden in the branch (e.g. the original line that was
 *       split).</li>
 * </ul>
 *
 * <p>Every read merges the delta over the base: a tombstoned id reads as absent, an added id shadows
 * the base, everything else falls through to the shared base. The base index is never mutated, so the
 * parent network and any sibling branch are unaffected — structural isolation without copying the
 * untouched graph. Memory is O(delta), not O(network).</p>
 *
 * <p>This mirrors the read surface of {@link NetworkIndex} used by {@code NetworkImpl}
 * ({@code get}, {@code get(id, class)}, {@code contains}, {@code getAll}, {@code getAll(class)}) plus
 * the two structural mutations a branch performs ({@code add}, {@code tombstone}).</p>
 *
 * @author Claude
 */
class OverlayNetworkIndex {

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
        // Fall through to the base's alias table by asking it to resolve; base.get(alias) already
        // resolves aliases, but here we only need the canonical id, so probe the object.
        Identifiable<?> baseObj = base.get(idOrAlias);
        return baseObj != null ? baseObj.getId() : idOrAlias;
    }

    Identifiable<?> get(String idOrAlias) {
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

    <T extends Identifiable> T get(String id, Class<T> clazz) {
        Identifiable<?> obj = get(id);
        if (obj != null && clazz.isAssignableFrom(obj.getClass())) {
            return (T) obj;
        }
        return null;
    }

    boolean contains(String idOrAlias) {
        return get(idOrAlias) != null;
    }

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

    /** Add an object that exists only in this branch. */
    void add(Identifiable<?> obj) {
        NetworkIndex.checkId(obj.getId());
        String id = obj.getId();
        if (get(id) != null) {
            throw new PowsyblException("Object '" + id + "' already exists in the overlay");
        }
        // Re-adding a previously tombstoned id resurrects it as a branch-local object.
        tombstoned.remove(id);
        addedById.put(id, obj);
        obj.getAliases().forEach(alias -> addedIdByAlias.put(alias, id));
        addedByClass.computeIfAbsent(obj.getClass(), k -> new LinkedHashSet<>()).add(obj);
    }

    /** Hide an object from this branch. A base object becomes a tombstone; a branch-local one is dropped. */
    void tombstone(Identifiable<?> obj) {
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
            throw new PowsyblException("Object '" + id + "' not found to tombstone");
        }
        tombstoned.add(id);
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
