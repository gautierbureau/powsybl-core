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

    // The thread that opened the current multi-threaded phase, i.e. the one the VariantManager contract calls
    // "main": while that phase is open it is the only thread allowed to structurally modify the network. Null
    // when multi-thread access is off. Volatile because worker threads read it to check themselves.
    private volatile Thread multiThreadAccessOwner;

    private final NetworkIndex networkIndex;

    private final BiMap<String, Integer> id2index = HashBiMap.create();

    private int variantArraySize;

    private final Deque<Integer> unusedIndexes = new ArrayDeque<>();

    private final NetworkImpl network;

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
        return Collections.unmodifiableSet(id2index.keySet());
    }

    /**
     * Get the size of the variant array
     * This size is different from the number of variants that also count unused but not released variants.
     *
     * @return the size of the variant array
     */
    public int getVariantArraySize() {
        return variantArraySize;
    }

    int getVariantCount() {
        return id2index.size();
    }

    Collection<Integer> getVariantIndexes() {
        return id2index.values();
    }

    private int getVariantIndex(String variantId) {
        Integer index = id2index.get(variantId);
        if (index == null) {
            throw new PowsyblException("Variant '" + variantId + "' not found");
        }
        return index;
    }

    public String getVariantId(int variantIndex) {
        return id2index.inverse().get(variantIndex);
    }

    @Override
    public String getWorkingVariantId() {
        int index = variantContext.getVariantIndex();
        return getVariantId(index);
    }

    @Override
    public void setWorkingVariant(String variantId) {
        int index = getVariantIndex(variantId);
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
            } else if (unusedIndexes.isEmpty()) {
                // extend variant array size
                id2index.put(targetVariantId, variantArraySize);
                variantArraySize++;
                extendedCount++;

                network.getListeners().notifyVariantCreated(sourceVariantId, targetVariantId);
            } else {
                // recycle an index
                int index = unusedIndexes.pollLast();
                id2index.put(targetVariantId, index);
                recycled.add(index);

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
        int index = getVariantIndex(variantId);
        id2index.remove(variantId);
        LOGGER.debug("Removing variant '{}'", variantId);
        if (index == variantArraySize - 1) {
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
            // Only on the off -> on transition, so that a redundant allow(true) from a worker thread does not
            // steal ownership from the thread that actually opened the multi-threaded phase.
            multiThreadAccessOwner = Thread.currentThread();
        } else if (!allow && !(variantContext instanceof MultiVariantContext)) {
            if (variantContext.isIndexSet()) {
                variantContext = new MultiVariantContext(variantContext.getVariantIndex());
            } else {
                // For singlethreaded VariantContext, set the variantIndex to a default value
                // if it is not set, because missing initialization error are rare.
                variantContext = new MultiVariantContext(INITIAL_VARIANT_INDEX);
            }
            multiThreadAccessOwner = null;
        }
    }

    /**
     * Fail if the calling thread is not allowed to structurally modify the columnar variant stores
     * (see {@link NumericVariantStore}) right now.
     *
     * <p>The {@link VariantManager} contract is that variants are pre-allocated, multi-thread access is enabled
     * from the main thread, worker threads then only read and write pre-allocated variants — each on its own
     * variant index — and structural changes resume on the main thread once that phase is over. A structural
     * store operation from a worker thread during that phase can resize a store (reassigning the backing array
     * and the row stride together) underneath a concurrent reader, which then indexes into the wrong variant or
     * out of bounds. Because the store is shared by every object of a type, that corrupts all of them at once
     * and usually surfaces as a plausible but wrong value rather than an exception.</p>
     *
     * <p>The check is therefore limited to exactly that window: it is inert while multi-thread access is off
     * (structural changes are then unconstrained, including after handing the network to another thread), and
     * it accepts structural changes from the thread that enabled multi-thread access, which the contract
     * explicitly allows.</p>
     *
     * <p>Not every store operation driven by a variant operation is checked, only those that can actually
     * corrupt a concurrent reader: the ones that reallocate the backing arrays, change the row stride or the
     * variant capacity, mutate the row bookkeeping, or write bands other threads own — {@code allocateRow},
     * {@code freeRow}, {@code fillInt}/{@code fillBoolean}, {@code extend} and {@code allocate}. The two
     * operations {@link #removeVariant(String)} drives are deliberately left unchecked: {@code reduce} only
     * decrements the live band count and {@code delete} is a no-op, neither touches geometry, and no getter or
     * per-variant setter reads the band count. Removing a variant from a worker thread is a supported pattern
     * (see {@code AbstractExceptionIsThrownWhenRemoveVariantAndWorkingVariantIsNotSetTest} in the TCK) and must
     * keep working.</p>
     */
    void checkStructuralModification(String store, String operation) {
        Thread owner = multiThreadAccessOwner;
        Thread current = Thread.currentThread();
        if (owner != null && owner != current) {
            throw new PowsyblException("Structural modification (" + operation + ") of the columnar variant store '"
                    + store + "' from thread '" + current.getName() + "' while multi-thread variant access is enabled"
                    + " and owned by thread '" + owner.getName() + "'. The VariantManager contract only allows"
                    + " worker threads to read and write pre-allocated variants; structural changes must happen on"
                    + " the thread that enabled multi-thread access. Doing this concurrently can resize the store"
                    + " under a reader and silently corrupt every object of this type.");
        }
    }

    @Override
    public boolean isVariantMultiThreadAccessAllowed() {
        return variantContext instanceof ThreadLocalMultiVariantContext;
    }

    void forEachVariant(Runnable r) {
        int currentVariantIndex = variantContext.getVariantIndex();
        try {
            for (int index : id2index.values()) {
                variantContext.setVariantIndex(index);
                r.run();
            }
        } finally {
            variantContext.setVariantIndex(currentVariantIndex);
        }
    }
}
