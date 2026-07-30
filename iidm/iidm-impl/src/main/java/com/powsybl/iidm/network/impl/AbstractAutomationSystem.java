/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.AutomationSystem;

import java.util.Objects;

/**
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
abstract class AbstractAutomationSystem<I extends AutomationSystem<I>> extends AbstractIdentifiable<I> implements AutomationSystem<I> {

    // enabled is held columnarly in a network-level NumericVariantStore (structure-of-arrays) shared by all
    // automation systems, so a variant clone copies it at once with a bulk array copy instead of per object.
    private static final String STORE_KEY = "AutomationSystem";
    private static final double[] DOUBLE_DEFAULTS = {};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_ENABLED = 0;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    AbstractAutomationSystem(Ref<NetworkImpl> networkRef, String id, String name, boolean enabled) {
        super(id, name);
        Objects.requireNonNull(networkRef);
        this.variantStore = networkRef.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {enabled});
    }

    @Override
    public boolean isEnabled() {
        return variantStore.getBoolean(getNetwork().getVariantIndex(), COL_ENABLED, variantStoreRow);
    }

    @Override
    public void setEnabled(boolean enabled) {
        variantStore.setBoolean(getNetwork().getVariantIndex(), COL_ENABLED, variantStoreRow, enabled);
    }

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
        // enabled handled by NumericVariantStore
    }

    @Override
    public void reduceVariantArraySize(int number) {
        super.reduceVariantArraySize(number);
        // handled by NumericVariantStore
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        super.deleteVariantArrayElement(index);
        // nothing to do
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);
        // handled by NumericVariantStore
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork);
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
    }
}
