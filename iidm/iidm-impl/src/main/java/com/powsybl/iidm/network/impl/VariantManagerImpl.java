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

    private final Deque<Integer> unusedIndexes = new ArrayDeque<>();

    // Clone parentage, copy-on-write (STRUCTURAL) variant marks and the copy-on-write master gate, shared
    // with every columnar variant store. Drives copy-on-write existence AND state resolution.
    private final VariantCowState cowState = new VariantCowState();

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
        cloneVariant(sourceVariantId, targetVariantIds, VariantCloneStrategy.STATE_ONLY, mayOverwrite);
    }

    @Override
    public void cloneVariant(String sourceVariantId, List<String> targetVariantIds, VariantCloneStrategy strategy, boolean mayOverwrite) {
        Objects.requireNonNull(strategy);
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

        boolean structural = strategy == VariantCloneStrategy.STRUCTURAL;

        // compute the stateful objects list only once for the whole clone operation
        List<MultiVariantObject> statefulObjects = getStafulObjects();

        // an overwritten variant may have copy-on-write children still inheriting state from it: freeze
        // that state into them before its bands are overwritten, so they keep their snapshot
        for (int index : overwritten) {
            network.materializeCowInheritorsOf(index);
        }

        // Record the clone parentage (and, for a STRUCTURAL clone, the copy-on-write marks) before driving
        // the owners, so the stores resolve through a consistent parentage while cloning. An object added
        // in a structural variant resolves its visibility through this same tree instead of being hidden in
        // every other variant eagerly. The parentage follows every clone, whatever the strategy.
        for (String targetVariantId : targetVariantIds) {
            cowState.recordClone(id2index.get(targetVariantId), sourceIndex, structural);
        }

        allocateVariantArrayElements(sourceIndex, recycled, overwritten, statefulObjects, structural);

        if (extendedCount > 0) {
            for (MultiVariantObject obj : statefulObjects) {
                obj.extendVariantArraySize(initVariantArraySize, extendedCount, sourceIndex, structural);
            }
            LOGGER.trace("Extending variant array size to {} (+{})", variantArraySize, extendedCount);
        }

        if (structural) {
            // the network resolves object existence and terminal membership against the active variant
            network.enableVariantScopedExistence();
            network.enableVariantScopedMembership();
        }
    }

    /** The shared copy-on-write bookkeeping (parentage, structural marks, master gate). */
    VariantCowState getCowState() {
        return cowState;
    }

    /** The variant a variant was cloned from, or {@code -1} for a root (the initial variant). */
    int parentVariant(int index) {
        return cowState.getParent(index);
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
                                              List<MultiVariantObject> statefulObjects, boolean structural) {
        if (!recycled.isEmpty()) {
            int[] indexes = Ints.toArray(recycled);
            for (MultiVariantObject obj : statefulObjects) {
                obj.allocateVariantArrayElement(indexes, sourceIndex, structural);
            }
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Recycling variant array indexes {}", Arrays.toString(indexes));
            }
        }
        if (!overwritten.isEmpty()) {
            int[] indexes = Ints.toArray(overwritten);
            for (MultiVariantObject obj : statefulObjects) {
                obj.allocateVariantArrayElement(indexes, sourceIndex, structural);
            }
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Overwriting variant array indexes {}", Arrays.toString(indexes));
            }
        }
    }

    /** Whether structural mutations (add/remove/reconnect) made now are scoped to the working variant. */
    boolean isCurrentVariantStructural() {
        return variantContext.isIndexSet() && cowState.isCow(variantContext.getVariantIndex());
    }

    @Override
    public void removeVariant(String variantId) {
        if (VariantManagerConstants.INITIAL_VARIANT_ID.equals(variantId)) {
            throw new PowsyblException("Removing initial variant is forbidden");
        }
        int index = getVariantIndex(variantId);
        // Freeze the state its copy-on-write children still inherit from it into them (they keep their
        // snapshot), then re-parent them onto its parent and forget its existence deltas (objects that
        // existed only in it become invisible; its index may be recycled).
        network.materializeCowInheritorsOf(index);
        cowState.forgetVariant(index);
        VariantScopedExistence existence = networkIndex.getVariantScopedExistence();
        if (existence != null) {
            existence.forgetVariant(index);
        }
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
