/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.primitives.Ints;
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class VariantManagerImpl implements VariantManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantManagerImpl.class);

    private static final int INITIAL_VARIANT_INDEX = 0;

    private VariantContext variantContext;

    private final NetworkIndex networkIndex;

    private final BiMap<String, Integer> id2index = HashBiMap.create();

    private int variantArraySize;

    // Set once preAllocateVariants has been called: opts this manager into the managed-capacity contract
    // (thread-safe on-demand creation into reserved slots, overflow throws, no array shrink while multi-thread
    // access is on). When false, behaviour is unchanged from the legacy variant manager.
    private boolean preAllocated;

    private final Deque<Integer> unusedIndexes = new ArrayDeque<>();

    private final NetworkImpl network;

    // Guards every read and write of the variant bookkeeping (id2index, unusedIndexes, variantArraySize).
    // The per-variant data arrays themselves are written concurrently by worker threads, each on its own
    // variant band; this lock only serialises variant creation/removal and the id<->index resolution, so
    // that on-demand creation into pre-allocated capacity (see preAllocateVariants) cannot race a concurrent
    // setWorkingVariant / creation on another thread. It is not held on the variant-dependent attribute hot
    // path (that resolves a thread-local index and reads the data arrays directly).
    private final Object variantLock = new Object();

    VariantManagerImpl(NetworkImpl network) {
        this.network = network;
        this.variantContext = new MultiVariantContext(INITIAL_VARIANT_INDEX);
        this.networkIndex = network.getIndex();
        // the network has always a zero index initial variant
        id2index.put(VariantManagerConstants.INITIAL_VARIANT_ID, INITIAL_VARIANT_INDEX);
        variantArraySize = INITIAL_VARIANT_INDEX + 1;
    }

    VariantContext getVariantContext() {
        return variantContext;
    }

    @Override
    public Collection<String> getVariantIds() {
        synchronized (variantLock) {
            // snapshot: the backing key set is a live view that could be mutated by a concurrent creation
            return Collections.unmodifiableSet(new LinkedHashSet<>(id2index.keySet()));
        }
    }

    /**
     * Get the size of the variant array
     * This size is different from the number of variants that also count unused but not released variants.
     *
     * @return the size of the variant array
     */
    public int getVariantArraySize() {
        synchronized (variantLock) {
            return variantArraySize;
        }
    }

    int getVariantCount() {
        synchronized (variantLock) {
            return id2index.size();
        }
    }

    Collection<Integer> getVariantIndexes() {
        synchronized (variantLock) {
            // id2index.values() is a Set (indexes are unique); snapshot it to avoid exposing the live view
            return new LinkedHashSet<>(id2index.values());
        }
    }

    // callers must hold variantLock
    private int getVariantIndex(String variantId) {
        Integer index = id2index.get(variantId);
        if (index == null) {
            throw new PowsyblException("Variant '" + variantId + "' not found");
        }
        return index;
    }

    public String getVariantId(int variantIndex) {
        synchronized (variantLock) {
            return id2index.inverse().get(variantIndex);
        }
    }

    @Override
    public String getWorkingVariantId() {
        int index = variantContext.getVariantIndex();
        return getVariantId(index);
    }

    @Override
    public void setWorkingVariant(String variantId) {
        int index;
        synchronized (variantLock) {
            index = getVariantIndex(variantId);
        }
        variantContext.setVariantIndex(index);
    }

    private List<MultiVariantObject> getStafulObjects() {
        // the list is cached and incrementally invalidated by the network index, so repeated variant
        // operations do not re-scan and re-filter all the network identifiables each time
        return networkIndex.getStatefulObjects();
    }

    @Override
    public void cloneVariant(String sourceVariantId, String targetVariantId) {
        cloneVariant(sourceVariantId, Collections.singletonList(targetVariantId), false);
    }

    @Override
    public void cloneVariant(String sourceVariantId, String targetVariantId, boolean mayOverwrite) {
        cloneVariant(sourceVariantId, Collections.singletonList(targetVariantId), mayOverwrite);
    }

    @Override
    public void cloneVariant(String sourceVariantId, List<String> targetVariantIds) {
        cloneVariant(sourceVariantId, targetVariantIds, false);
    }

    @Override
    public void cloneVariant(String sourceVariantId, List<String> targetVariantIds, boolean mayOverwrite) {
        if (targetVariantIds.isEmpty()) {
            throw new IllegalArgumentException("Empty target variant id list");
        }
        LOGGER.debug("Creating variants {}", targetVariantIds);
        synchronized (variantLock) {
            if (!mayOverwrite) {
                checkExistingVariantIds(targetVariantIds);
            }
            int sourceIndex = getVariantIndex(sourceVariantId);
            int initVariantArraySize = variantArraySize;
            int extendedCount = 0;
            List<Integer> recycled = new ArrayList<>();
            List<Integer> overwritten = new ArrayList<>();
            for (String targetVariantId : targetVariantIds) {
                if (id2index.containsKey(targetVariantId)) {
                    overwritten.add(id2index.get(targetVariantId));

                    network.getListeners().notifyVariantOverwritten(sourceVariantId, targetVariantId);
                } else if (!unusedIndexes.isEmpty()) {
                    // reuse a free slot (recycled from a removed variant, or reserved by preAllocateVariants):
                    // this only overwrites an existing band, it never resizes the per-variant arrays, so it is
                    // safe to run while multi-thread access is enabled
                    int index = unusedIndexes.pollLast();
                    id2index.put(targetVariantId, index);
                    recycled.add(index);

                    network.getListeners().notifyVariantCreated(sourceVariantId, targetVariantId);
                } else if (preAllocated && isVariantMultiThreadAccessAllowed()) {
                    // managed-capacity mode with no reserved slot left: extending the arrays now would resize
                    // them from a (possibly worker) thread while other threads read/write variants, which is
                    // not thread safe. Fail fast instead - callers must reserve enough capacity up front.
                    throw new PowsyblException("No pre-allocated variant capacity left to create variant '"
                            + targetVariantId + "' while multi-thread access is enabled; reserve capacity with "
                            + "preAllocateVariants(int) before calling allowVariantMultiThreadAccess(true)");
                } else {
                    // extend variant array size (main thread, single-thread access)
                    id2index.put(targetVariantId, variantArraySize);
                    variantArraySize++;
                    extendedCount++;

                    network.getListeners().notifyVariantCreated(sourceVariantId, targetVariantId);
                }
            }

            // compute the stateful objects list only once for the whole clone operation
            List<MultiVariantObject> statefulObjects = getStafulObjects();

            allocateVariantArrayElements(sourceIndex, recycled, overwritten, statefulObjects);

            if (extendedCount > 0) {
                for (MultiVariantObject obj : statefulObjects) {
                    obj.extendVariantArraySize(initVariantArraySize, extendedCount, sourceIndex);
                }
                LOGGER.trace("Extending variant array size to {} (+{})", variantArraySize, extendedCount);
            }
        }
    }

    @Override
    public void preAllocateVariants(int number) {
        if (number < 0) {
            throw new IllegalArgumentException("Number of variants to pre-allocate must be >= 0, got " + number);
        }
        synchronized (variantLock) {
            if (number == 0) {
                return;
            }
            int initVariantArraySize = variantArraySize;
            // grow every per-variant array once, on the main thread, initialising the reserved slots from the
            // initial variant; this reuses the exact same extend path as a normal clone
            List<MultiVariantObject> statefulObjects = getStafulObjects();
            for (MultiVariantObject obj : statefulObjects) {
                obj.extendVariantArraySize(initVariantArraySize, number, INITIAL_VARIANT_INDEX);
            }
            // park the freshly grown indexes as reusable capacity (not yet live variants); a later clone
            // will claim them through the recycle path, which does not resize anything
            for (int i = 0; i < number; i++) {
                unusedIndexes.add(initVariantArraySize + i);
            }
            variantArraySize += number;
            preAllocated = true;
            LOGGER.debug("Pre-allocated {} variant slot(s) (variant array size is now {})", number, variantArraySize);
        }
    }

    private void checkExistingVariantIds(List<String> targetVariantIds) {
        List<String> alreadyExistingVariantIds = targetVariantIds.stream()
                .filter(id2index::containsKey)
                .toList();

        if (!alreadyExistingVariantIds.isEmpty()) {
            throw new PowsyblException("Target variants already exist: " + alreadyExistingVariantIds);
        }
    }

    private void allocateVariantArrayElements(Integer sourceIndex, List<Integer> recycled, List<Integer> overwritten,
                                              List<MultiVariantObject> statefulObjects) {
        if (!recycled.isEmpty()) {
            int[] indexes = Ints.toArray(recycled);
            for (MultiVariantObject obj : statefulObjects) {
                obj.allocateVariantArrayElement(indexes, sourceIndex);
            }
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Recycling variant array indexes {}", Arrays.toString(indexes));
            }
        }
        if (!overwritten.isEmpty()) {
            int[] indexes = Ints.toArray(overwritten);
            for (MultiVariantObject obj : statefulObjects) {
                obj.allocateVariantArrayElement(indexes, sourceIndex);
            }
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Overwriting variant array indexes {}", Arrays.toString(indexes));
            }
        }
    }

    @Override
    public void removeVariant(String variantId) {
        if (VariantManagerConstants.INITIAL_VARIANT_ID.equals(variantId)) {
            throw new PowsyblException("Removing initial variant is forbidden");
        }
        synchronized (variantLock) {
            int index = getVariantIndex(variantId);
            id2index.remove(variantId);
            LOGGER.debug("Removing variant '{}'", variantId);
            // In managed-capacity mode while multi-thread access is enabled the per-variant arrays must not be
            // shrunk (that would resize them while other threads read/write variants); the freed slot is only
            // parked for reuse, preserving the reserved capacity. Shrinking resumes once single-thread access
            // is restored. Outside that mode, behaviour is unchanged.
            if (index == variantArraySize - 1 && !(preAllocated && isVariantMultiThreadAccessAllowed())) {
                // remove consecutive unsused index starting from the end
                int number = 0; // number of elements to remove
                Set<Integer> removed = new HashSet<>();
                for (int j = index; j >= 0; j--) {
                    if (id2index.containsValue(j)) {
                        break;
                    } else {
                        number++;
                        removed.add(j);
                    }
                }
                unusedIndexes.removeAll(removed);
                // reduce variant array size
                for (MultiVariantObject obj : getStafulObjects()) {
                    obj.reduceVariantArraySize(number);
                }
                variantArraySize -= number;
                LOGGER.trace("Reducing variant array size to {}", variantArraySize);
            } else {
                unusedIndexes.add(index);
                // delete variant array element at the unused index to avoid memory leak
                // (so that variant data can be garbage collected)
                for (MultiVariantObject obj : getStafulObjects()) {
                    obj.deleteVariantArrayElement(index);
                }
                LOGGER.trace("Deleting variant array element at index {}", index);
            }
            // if the removed variant is the working variant, unset the working variant
            variantContext.resetIfVariantIndexIs(index);

            network.getListeners().notifyVariantRemoved(variantId);
        }
    }

    @Override
    public void allowVariantMultiThreadAccess(boolean allow) {
        if (allow && !(variantContext instanceof ThreadLocalMultiVariantContext)) {
            VariantContext newVariantContext = new ThreadLocalMultiVariantContext();
            // For multithreaded VariantContext, don't set the variantIndex to a default
            // value if it is not set, so that missing initializations fail fast.
            if (variantContext.isIndexSet()) {
                newVariantContext.setVariantIndex(variantContext.getVariantIndex());
            }
            variantContext = newVariantContext;
        } else if (!allow && !(variantContext instanceof MultiVariantContext)) {
            if (variantContext.isIndexSet()) {
                variantContext = new MultiVariantContext(variantContext.getVariantIndex());
            } else {
                // For singlethreaded VariantContext, set the variantIndex to a default value
                // if it is not set, because missing initialization error are rare.
                variantContext = new MultiVariantContext(INITIAL_VARIANT_INDEX);
            }
        }
    }

    @Override
    public boolean isVariantMultiThreadAccessAllowed() {
        return variantContext instanceof ThreadLocalMultiVariantContext;
    }

    void forEachVariant(Runnable r) {
        int currentVariantIndex = variantContext.getVariantIndex();
        List<Integer> indexes;
        synchronized (variantLock) {
            indexes = new ArrayList<>(id2index.values());
        }
        try {
            for (int index : indexes) {
                variantContext.setVariantIndex(index);
                r.run();
            }
        } finally {
            variantContext.setVariantIndex(currentVariantIndex);
        }
    }
}
