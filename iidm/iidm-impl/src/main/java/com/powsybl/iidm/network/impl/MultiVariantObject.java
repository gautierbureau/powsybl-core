/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

/**
 * An interface implemented by network objects that have attributes depending on
 * the variant.
 * <p>
 * A class implementing this interface internally manages an array of variants and
 * is notified when the array need to be resized thanks to <code>extendVariantArraySize</code>
 * and <code>reduceVariantArraySize</code> callbacks.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface MultiVariantObject {

    /**
     * Called to extend the variant array.
     *
     * @param initVariantArraySize initial variant array size
     * @param number number of element to add
     * @param sourceIndex the variant index to use to initialize new variants
     */
    void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex);

    /**
     * Called to reduce the variant array.
     *
     * @param number number of element to remove
     */
    void reduceVariantArraySize(int number);

    /**
     * Called to delete a variant array element.
     *
     * @param index the index of the variant array to delete
     */
    void deleteVariantArrayElement(int index);

    /**
     * Called to allocate a variant array element. All new variants will be initialize using values of the variant sourceIndex.
     *
     * @param indexes the indexes of the variant array to allocate
     * @param sourceIndex the variant index to use to initialize new variants
     */
    void allocateVariantArrayElement(int[] indexes, int sourceIndex);

    /**
     * Strategy-aware variant of {@link #extendVariantArraySize(int, int, int)}, called by the variant
     * manager so a {@code STRUCTURAL} clone can be stored copy-on-write. The default ignores the flag and
     * performs the eager copy, which is always correct: only owners that support copy-on-write storage
     * (see {@link NetworkImpl}) override this.
     */
    default void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex, boolean structuralClone) {
        extendVariantArraySize(initVariantArraySize, number, sourceIndex);
    }

    /**
     * Strategy-aware variant of {@link #allocateVariantArrayElement(int[], int)}; same contract as
     * {@link #extendVariantArraySize(int, int, int, boolean)}.
     */
    default void allocateVariantArrayElement(int[] indexes, int sourceIndex, boolean structuralClone) {
        allocateVariantArrayElement(indexes, sourceIndex);
    }

    /**
     * Called when this object changes network (merge/detach) to move its columnar variant state
     * (see {@link NumericVariantStore}) into {@code targetNetwork}'s stores, before the network reference is
     * redirected. Implementations re-home their own rows and cascade to their children, mirroring the
     * {@code extendVariantArraySize} cascade. Default is a no-op for objects with no columnar state. Only
     * single-variant networks can be merged/detached, so only the initial variant is transferred.
     */
    default void reHomeVariantStores(NetworkImpl targetNetwork) {
        // no columnar state by default
    }
}
