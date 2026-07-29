/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Identifiable;

import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class NetworkIndex {

    // Thread-safety: identifiables may be created and removed from several threads at once (one variant per
    // thread), while reads stay far more frequent than writes. Writers serialise on writeLock -- checkAndAdd
    // is a read-modify-write spanning several of these maps and has to be atomic as a whole -- and readers
    // never take it, resolving through concurrent maps and volatile snapshots instead.
    private final Object writeLock = new Object();

    // Kept a plain HashMap, published through a volatile reference: getAll() hands its values straight to
    // callers that serialize them, so its iteration order is part of the written output and swapping in a
    // ConcurrentHashMap (a different hash spread, so a different order) rewrites XML that reference files
    // pin. Mutated the same way as the per-class sets below -- in place normally, as a mutated copy while
    // writes can be concurrent.
    private volatile Map<String, Identifiable<?>> objectsById = new HashMap<>();
    // Overflow holder for the rare case of several objects sharing an id across sibling variants (an id added
    // independently in two variants, or removed then re-added in one). Empty for every normal network and
    // while no such collision exists, so the single-object hot path is unchanged. The object in objectsById is
    // the primary; the extras live here. At most one object per id is visible in a variant.
    private final Map<String, List<Identifiable<?>>> extraObjectsById = new ConcurrentHashMap<>();

    private final Map<String, String> idByAlias = new ConcurrentHashMap<>();

    private final Map<Class<? extends Identifiable>, ClassBucket> objectsByClass = new ConcurrentHashMap<>();

    // Lazily-built list of the multi-variant objects (a subset of objectsById). Variant operations
    // (clone/remove) iterate over it once per operation; caching it avoids re-scanning and re-filtering
    // all the network identifiables on each variant operation. Invalidated whenever an identifiable is
    // added or removed.
    private volatile List<MultiVariantObject> statefulObjectsCache;

    // Tells whether identifiables may currently be created/removed concurrently. While that is off -- every
    // network build, and every single-threaded use -- the per-class sets are mutated in place, which keeps
    // adding n objects O(n). While it is on, they are replaced by a mutated copy instead, so a reader
    // iterating one is never disturbed. Set by the network once its variant manager exists.
    private BooleanSupplier concurrentWrites = () -> false;

    void setConcurrentWritesProbe(BooleanSupplier concurrentWrites) {
        this.concurrentWrites = concurrentWrites;
    }

    // Callers must hold writeLock.
    private void putObject(String id, Identifiable<?> obj) {
        if (concurrentWrites.getAsBoolean()) {
            Map<String, Identifiable<?>> copy = new HashMap<>(objectsById);
            copy.put(id, obj);
            objectsById = copy;
        } else {
            objectsById.put(id, obj);
        }
    }

    // Callers must hold writeLock.
    private void removeObject(String id) {
        if (concurrentWrites.getAsBoolean()) {
            Map<String, Identifiable<?>> copy = new HashMap<>(objectsById);
            copy.remove(id);
            objectsById = copy;
        } else {
            objectsById.remove(id);
        }
    }

    /**
     * The identifiables of one concrete class, in insertion order -- which is the order they are serialized
     * in, so it has to be preserved. The set is published through a volatile reference: readers iterate
     * whatever snapshot they got, writers (always under {@link #writeLock}) either mutate it in place or swap
     * in a mutated copy, depending on whether anything could be reading concurrently.
     */
    private static final class ClassBucket {

        private volatile Set<Identifiable<?>> objects = new LinkedHashSet<>();

        Set<Identifiable<?>> get() {
            return objects;
        }

        void add(Identifiable<?> obj, boolean copyOnWrite) {
            if (copyOnWrite) {
                Set<Identifiable<?>> copy = new LinkedHashSet<>(objects);
                copy.add(obj);
                objects = copy;
            } else {
                objects.add(obj);
            }
        }

        void remove(Identifiable<?> obj, boolean copyOnWrite) {
            if (copyOnWrite) {
                Set<Identifiable<?>> copy = new LinkedHashSet<>(objects);
                copy.remove(obj);
                objects = copy;
            } else {
                objects.remove(obj);
            }
        }
    }

    // Variant-scoped existence: when set, an object's existence is a function of the active variant.
    // Null for every normal network, so get/getAll/contains keep their exact hot path.
    private VariantScopedExistence existence;

    // Variant-scoped terminal membership. Not consulted by the index (the topology-model folds read it);
    // held here only so the variant lifecycle grows/copies its per-variant maps.
    private VariantScopedMembership membership;

    void setVariantScopedExistence(VariantScopedExistence existence) {
        this.existence = existence;
        this.statefulObjectsCache = null;
    }

    VariantScopedExistence getVariantScopedExistence() {
        return existence;
    }

    void setVariantScopedMembership(VariantScopedMembership membership) {
        this.membership = membership;
        this.statefulObjectsCache = null;
    }

    VariantScopedMembership getVariantScopedMembership() {
        return membership;
    }

    static void checkId(String id) {
        if (id == null || id.isEmpty()) {
            throw new PowsyblException("Invalid id '" + id + "'");
        }
    }

    static String getUniqueId() {
        return UUID.randomUUID().toString();
    }

    void checkAndAdd(Identifiable<?> obj) {
        checkId(obj.getId());
        synchronized (writeLock) {
            doCheckAndAdd(obj);
        }
    }

    private void doCheckAndAdd(Identifiable<?> obj) {
        String id = obj.getId();
        boolean variantScoped = existence != null && existence.isVariantScopedStructure();
        Identifiable<?> primary = objectsById.get(id);
        List<Identifiable<?>> extras = extraObjectsById.get(id);
        boolean idAlreadyUsed = primary != null || extras != null && !extras.isEmpty();
        if (idAlreadyUsed) {
            // While the network has a single variant an id is unique network-wide. Once it has several, an id
            // collides only if a same-id object is actually visible in the active variant (e.g. a base object
            // still present, or one added in this variant); if the existing objects are all hidden here (added
            // in a sibling, or tombstoned), the new object is a distinct variant-scoped object sharing the id.
            if (!variantScoped || isAnySameIdVisible(primary, extras)) {
                throw new PowsyblException("Object (" + obj.getClass().getName()
                        + ") '" + id + "' already exists");
            }
            markScoped(obj, variantScoped);
            addExtra(id, obj);
        } else {
            markScoped(obj, variantScoped);
            putObject(id, obj);
        }
        obj.getAliases().forEach(alias -> addAlias(obj, alias));

        objectsByClass.computeIfAbsent(obj.getClass(), k -> new ClassBucket()).add(obj, concurrentWrites.getAsBoolean());
        statefulObjectsCache = null;
    }

    /**
     * Once the network has several variants, an object added while one of them is the working variant exists
     * only in that variant (a connectable, a container VL/bus/substation — anything). Its terminal membership
     * is handled separately by the topology-model branch-attach intercept.
     * <p>
     * This must happen <em>before</em> the object is published in the index. Otherwise another thread can find
     * it in the window between the two and, seeing nothing marking it as variant-scoped, resolve it as a base
     * object visible in every variant — which is how a worker briefly saw another worker's new equipment.
     */
    private void markScoped(Identifiable<?> obj, boolean variantScoped) {
        if (variantScoped) {
            existence.existOnlyInCurrentVariant(obj);
        }
    }

    private boolean isAnySameIdVisible(Identifiable<?> primary, List<Identifiable<?>> extras) {
        if (primary != null && existence.isVisible(primary)) {
            return true;
        }
        if (extras != null) {
            for (Identifiable<?> e : extras) {
                if (existence.isVisible(e)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addExtra(String id, Identifiable<?> obj) {
        extraObjectsById.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(obj);
    }

    /** Resolve an id to the single object visible in the active variant (null if none), across collisions. */
    private Identifiable<?> resolveVisible(String id) {
        Identifiable<?> primary = objectsById.get(id);
        if (existence == null) {
            return primary;
        }
        List<Identifiable<?>> extras = extraObjectsById.get(id);
        if (extras == null) {
            return primary != null && existence.isVisible(primary) ? primary : null;
        }
        if (primary != null && existence.isVisible(primary)) {
            return primary;
        }
        for (Identifiable<?> e : extras) {
            if (existence.isVisible(e)) {
                return e;
            }
        }
        return null;
    }

    boolean addAlias(Identifiable<?> obj, String alias) {
        Identifiable<?> aliasConflict = objectsById.get(alias);
        if (aliasConflict != null) {
            if (aliasConflict.equals(obj)) {
                // Silently ignore affecting the objects id to its own aliases
                return false;
            }
            String message = String.format("Object (%s) with alias '%s' cannot be created because alias already refers to object (%s) with ID '%s'",
                    obj.getClass(),
                    alias,
                    aliasConflict.getClass(),
                    aliasConflict.getId());
            throw new PowsyblException(message);
        }
        String idForAlias = idByAlias.get(alias);
        if (idForAlias != null) {
            aliasConflict = objectsById.get(idForAlias);
            if (aliasConflict.equals(obj)) {
                // Silently ignore affecting the same alias twice to an object
                return false;
            }
            String message = String.format("Object (%s) with alias '%s' cannot be created because alias already refers to object (%s) with ID '%s'",
                    obj.getClass(),
                    alias,
                    aliasConflict.getClass(),
                    aliasConflict.getId());
            throw new PowsyblException(message);
        }
        idByAlias.put(alias, obj.getId());
        return true;
    }

    public <I extends Identifiable<I>> void removeAlias(Identifiable<?> obj, String alias) {
        String idForAlias = idByAlias.get(alias);
        if (idForAlias == null) {
            throw new PowsyblException(String.format("No alias '%s' found in the network", alias));
        } else if (!idForAlias.equals(obj.getId())) {
            throw new PowsyblException(String.format("Alias '%s' do not correspond to object '%s'", alias, obj.getId()));
        } else {
            idByAlias.remove(alias);
        }
    }

    Identifiable get(String idOrAlias) {
        String id = idByAlias.getOrDefault(idOrAlias, idOrAlias);
        checkId(id);
        return resolveVisible(id);
    }

    <T extends Identifiable> T get(String id, Class<T> clazz) {
        Identifiable<?> obj = get(id);
        if (obj != null && clazz.isAssignableFrom(obj.getClass())) {
            return (T) obj;
        } else {
            return null;
        }
    }

    Collection<Identifiable<?>> getAll() {
        if (existence == null || !existence.anyScoped()) {
            return objectsById.values();
        }
        List<Identifiable<?>> visible = new ArrayList<>(objectsById.size());
        for (Identifiable<?> obj : objectsById.values()) {
            if (existence.isVisible(obj)) {
                visible.add(obj);
            }
        }
        for (List<Identifiable<?>> extras : extraObjectsById.values()) {
            for (Identifiable<?> obj : extras) {
                if (existence.isVisible(obj)) {
                    visible.add(obj);
                }
            }
        }
        return visible;
    }

    /**
     * Return the multi-variant objects held by this index. The returned list is cached and rebuilt lazily
     * after any structural change (add/remove/clean); callers must only iterate over it, not mutate it.
     */
    List<MultiVariantObject> getStatefulObjects() {
        // Read the cache once. Re-reading the field to return it would hand back null whenever another thread
        // invalidates it in between (a concurrent creation does exactly that, under the index write lock while
        // this runs under the variant lock), which is how a clone got a null stateful-objects list.
        List<MultiVariantObject> cached = statefulObjectsCache;
        if (cached == null) {
            List<MultiVariantObject> stateful = new ArrayList<>(objectsById.size());
            for (Identifiable<?> obj : objectsById.values()) {
                if (obj instanceof MultiVariantObject multiVariantObject) {
                    stateful.add(multiVariantObject);
                }
            }
            for (List<Identifiable<?>> extras : extraObjectsById.values()) {
                for (Identifiable<?> obj : extras) {
                    if (obj instanceof MultiVariantObject multiVariantObject) {
                        stateful.add(multiVariantObject);
                    }
                }
            }
            // existence and membership are variant state too — a clone must grow/copy their columns with
            // the rest
            if (existence != null) {
                stateful.add(existence);
            }
            if (membership != null) {
                stateful.add(membership);
            }
            statefulObjectsCache = stateful;
            return stateful;
        }
        return cached;
    }

    <T extends Identifiable> Set<T> getAll(Class<T> clazz) {
        ClassBucket bucket = objectsByClass.get(clazz);
        Set<Identifiable<?>> all = bucket == null ? null : bucket.get();
        if (all == null) {
            return Collections.emptySet();
        }
        if (existence == null || !existence.anyScoped()) {
            return (Set<T>) all;
        }
        Set<Identifiable<?>> visible = new LinkedHashSet<>(all.size());
        for (Identifiable<?> obj : all) {
            if (existence.isVisible(obj)) {
                visible.add(obj);
            }
        }
        return (Set<T>) visible;
    }

    boolean contains(String id) {
        String idFromPotentialAlias = idByAlias.getOrDefault(id, id);
        checkId(idFromPotentialAlias);
        return resolveVisible(idFromPotentialAlias) != null;
    }

    void remove(Identifiable obj) {
        synchronized (writeLock) {
            doRemove(obj);
        }
    }

    /**
     * Remove an object that the variant lifecycle itself is dropping — an object whose only variant has just
     * been removed. Same thing as {@link #remove}; the separate entry point is kept for the call site's sake.
     */
    void removeVariantOrphan(Identifiable<?> obj) {
        synchronized (writeLock) {
            doRemove(obj);
        }
    }

    private void doRemove(Identifiable obj) {
        checkId(obj.getId());
        String id = obj.getId();
        Identifiable<?> primary = objectsById.get(id);
        if (primary == obj) {
            // remove the primary; promote an extra (a same-id variant-scoped object) to primary if any
            List<Identifiable<?>> extras = extraObjectsById.get(id);
            if (extras != null && !extras.isEmpty()) {
                putObject(id, extras.remove(0));
                if (extras.isEmpty()) {
                    extraObjectsById.remove(id);
                }
            } else {
                removeObject(id);
            }
        } else {
            List<Identifiable<?>> extras = extraObjectsById.get(id);
            if (extras == null || !extras.remove(obj)) {
                throw new PowsyblException("Object (" + obj.getClass().getName()
                        + ") '" + id + "' not found");
            }
            if (extras.isEmpty()) {
                extraObjectsById.remove(id);
            }
        }
        obj.getAliases().forEach(idByAlias::remove);
        ClassBucket bucket = objectsByClass.get(obj.getClass());
        if (bucket != null) {
            bucket.remove(obj, concurrentWrites.getAsBoolean());
        }
        if (existence != null) {
            existence.forgetObject(obj);
        }
        statefulObjectsCache = null;
    }

    void clean() {
        synchronized (writeLock) {
            objectsById = new HashMap<>();
            objectsByClass.clear();
            extraObjectsById.clear();
            statefulObjectsCache = null;
        }
    }

    /**
     * Compute intersection between this index and another one.
     * @param other the other index
     * @return list of objects id or alias that exist in both indexes organized by class.
     */
    Multimap<Class<? extends Identifiable>, String> intersection(NetworkIndex other) {
        Multimap<Class<? extends Identifiable>, String> intersection = HashMultimap.create();
        for (Map.Entry<Class<? extends Identifiable>, ClassBucket> entry : other.objectsByClass.entrySet()) {
            Class<? extends Identifiable> clazz = entry.getKey();
            Set<Identifiable<?>> objects = entry.getValue().get();
            for (Identifiable obj : objects) {
                if (objectsById.containsKey(obj.getId()) || idByAlias.containsKey(obj.getId())) {
                    intersection.put(clazz, obj.getId());
                }
                Set<String> aliases = obj.getAliases();
                for (String alias : aliases) {
                    if (objectsById.containsKey(alias) || idByAlias.containsKey(alias)) {
                        intersection.put(clazz, alias);
                    }
                }
            }
        }
        return intersection;
    }

    /**
     * Merge an other index into this one. At the end of the call the
     * other index is empty.
     * @param other the index to merge
     */
    void merge(NetworkIndex other) {
        for (Identifiable obj : other.objectsById.values()) {
            checkAndAdd(obj);
        }
        other.clean();
    }

    void printForDebug(PrintStream out) {
        for (Map.Entry<String, Identifiable<?>> entry : objectsById.entrySet()) {
            out.println(entry.getKey() + " " + System.identityHashCode(entry.getValue()));
        }
        for (Map.Entry<Class<? extends Identifiable>, ClassBucket> entry : objectsByClass.entrySet()) {
            out.println(entry.getKey() + " " + entry.getValue().get().stream().map(System::identityHashCode).toList());
        }
    }
}
