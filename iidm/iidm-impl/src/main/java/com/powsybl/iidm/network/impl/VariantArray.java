/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import java.util.ArrayList;
import java.util.List;

/**
 * To easily manage an array of variant.
 *
 * <p>The variant list is published through a {@code volatile} reference and structural changes
 * (push/pop/delete/allocate) rebuild a new list (copy-on-write) before publishing it. Reads
 * ({@link #get()}, {@link #copy(int)}) are therefore lock-free: they read the {@code volatile}
 * reference and index into a list that is never structurally modified after publication.
 * This matches the {@link VariantManager} thread-safety contract, in which structural changes
 * are performed on the main thread only (never concurrently with variant reads/writes), while
 * different threads may read/write pre-allocated variants simultaneously (each thread on its own
 * variant index). The {@code volatile} publication guarantees those threads observe the fully
 * constructed variant list, without taking a lock on every read as {@code synchronizedList} did.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class VariantArray<S extends Variant> {

    private final Ref<? extends VariantManagerHolder> variantManagerHolder;

    private volatile List<S> variants;

    // Retained so a slot can be materialised on demand; see get().
    private final VariantFactory<S> variantFactory;

    VariantArray(Ref<? extends VariantManagerHolder> variantManagerHolder, VariantFactory<S> variantFactory) {
        this.variantManagerHolder = variantManagerHolder;
        this.variantFactory = variantFactory;
        VariantManagerImpl variantManager = variantManagerHolder.get().getVariantManager();
        List<S> initialVariants = new ArrayList<>(variantManager.getVariantArraySize());
        for (int i = 0; i < variantManager.getVariantArraySize(); i++) {
            initialVariants.add(null);
        }
        for (int i : variantManager.getVariantIndexes()) {
            initialVariants.set(i, variantFactory.newVariant());
        }
        this.variants = initialVariants;
    }

    private int getVariantIndex() {
        return variantManagerHolder.get().getVariantManager().getVariantContext().getVariantIndex();
    }

    /**
     * The variant state for the working variant, materialising it if this array was never extended to cover
     * that variant.
     *
     * <p>That happens when the owner was published in the network index after a clone had already taken its
     * stateful-objects snapshot, so the clone could not extend it. The owner did not exist as far as the
     * network was concerned when that variant was forked, so a fresh variant state is exactly what it would
     * have been given had the clone seen it — see {@code VariantRefArray} for the full argument.</p>
     */
    S get() {
        List<S> current = variants;
        int index = getVariantIndex();
        return index < current.size() ? current.get(index) : materialize(index);
    }

    private synchronized S materialize(int index) {
        List<S> current = variants;
        if (index < current.size()) {
            return current.get(index); // another thread got there first
        }
        List<S> newVariants = new ArrayList<>(current);
        while (newVariants.size() <= index) {
            newVariants.add(variantFactory.newVariant());
        }
        variants = newVariants;
        return newVariants.get(index);
    }

    void push(int number, VariantFactory<S> variantFactory) {
        List<S> newVariants = new ArrayList<>(variants);
        for (int i = 0; i < number; i++) {
            newVariants.add(variantFactory.newVariant());
        }
        variants = newVariants;
    }

    void push(VariantFactory<S> variantFactory) {
        List<S> newVariants = new ArrayList<>(variants);
        newVariants.add(variantFactory.newVariant());
        variants = newVariants;
    }

    void pop(int number) {
        List<S> newVariants = new ArrayList<>(variants);
        for (int i = 0; i < number; i++) {
            newVariants.remove(newVariants.size() - 1);
        }
        variants = newVariants;
    }

    void delete(int index) {
        List<S> newVariants = new ArrayList<>(variants);
        newVariants.set(index, null);
        variants = newVariants;
    }

    void allocate(int[] indexes, VariantFactory<S> variantFactory) {
        List<S> newVariants = new ArrayList<>(variants);
        for (int index : indexes) {
            newVariants.set(index, variantFactory.newVariant());
        }
        variants = newVariants;
    }

    S copy(int index) {
        return variants.get(index).copy();
    }

}
