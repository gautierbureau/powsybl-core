/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.extensions.LoadDetail;
import com.powsybl.iidm.network.impl.AbstractMultiVariantIdentifiableExtension;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;

/**
 * @author Miora Ralambotiana {@literal <miora.ralambotiana at rte-france.com>}
 */
public class LoadDetailImpl extends AbstractMultiVariantIdentifiableExtension<Load> implements LoadDetail {

    // fixed/variable active & reactive power (double), held columnarly
    private static final String STORE_KEY = "LoadDetail";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_FIXED_ACTIVE_POWER = 0;
    private static final int COL_FIXED_REACTIVE_POWER = 1;
    private static final int COL_VARIABLE_ACTIVE_POWER = 2;
    private static final int COL_VARIABLE_REACTIVE_POWER = 3;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    public LoadDetailImpl(Load load, double fixedActivePower, double fixedReactivePower,
                double variableActivePower, double variableReactivePower) {
        super(load);
        double checkedFixedActivePower = checkPower(fixedActivePower, "Invalid fixedActivePower", load);
        double checkedFixedReactivePower = checkPower(fixedReactivePower, "Invalid fixedReactivePower", load);
        double checkedVariableActivePower = checkPower(variableActivePower, "Invalid variableActivePower", load);
        double checkedVariableReactivePower = checkPower(variableReactivePower, "Invalid variableReactivePower", load);
        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {checkedFixedActivePower, checkedFixedReactivePower, checkedVariableActivePower, checkedVariableReactivePower},
                INT_DEFAULTS, BOOLEAN_DEFAULTS);
    }

    public double getFixedActivePower() {
        return variantStore.getDouble(getVariantIndex(), COL_FIXED_ACTIVE_POWER, variantStoreRow);
    }

    @Override
    public LoadDetail setFixedActivePower(double fixedActivePower) {
        checkPower(fixedActivePower, "Invalid fixedActivePower", this.getExtendable());
        variantStore.setDouble(getVariantIndex(), COL_FIXED_ACTIVE_POWER, variantStoreRow, fixedActivePower);
        return this;
    }

    @Override
    public double getFixedReactivePower() {
        return variantStore.getDouble(getVariantIndex(), COL_FIXED_REACTIVE_POWER, variantStoreRow);
    }

    @Override
    public LoadDetail setFixedReactivePower(double fixedReactivePower) {
        checkPower(fixedReactivePower, "Invalid fixedReactivePower", this.getExtendable());
        variantStore.setDouble(getVariantIndex(), COL_FIXED_REACTIVE_POWER, variantStoreRow, fixedReactivePower);
        return this;
    }

    @Override
    public double getVariableActivePower() {
        return variantStore.getDouble(getVariantIndex(), COL_VARIABLE_ACTIVE_POWER, variantStoreRow);
    }

    @Override
    public LoadDetail setVariableActivePower(double variableActivePower) {
        checkPower(variableActivePower, "Invalid variableActivePower", this.getExtendable());
        variantStore.setDouble(getVariantIndex(), COL_VARIABLE_ACTIVE_POWER, variantStoreRow, variableActivePower);
        return this;
    }

    @Override
    public double getVariableReactivePower() {
        return variantStore.getDouble(getVariantIndex(), COL_VARIABLE_REACTIVE_POWER, variantStoreRow);
    }

    @Override
    public LoadDetail setVariableReactivePower(double variableReactivePower) {
        checkPower(variableReactivePower, "Invalid variableReactivePower", this.getExtendable());
        variantStore.setDouble(getVariantIndex(), COL_VARIABLE_REACTIVE_POWER, variantStoreRow, variableReactivePower);
        return this;
    }

    private static double checkPower(double power, String errorMessage, Load load) {
        if (Double.isNaN(power)) {
            throw new IllegalArgumentException(String.format("%s (%s) for load %s",
                errorMessage,
                power,
                load.getId()));
        }
        return power;
    }

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        // handled by NumericVariantStore
    }

    @Override
    public void reduceVariantArraySize(int number) {
        // handled by NumericVariantStore
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        // Does nothing
        // TODO: maybe set default/undefined values for deleted variant index
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        // handled by NumericVariantStore
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        double fixedActivePower0 = variantStore.getDouble(0, COL_FIXED_ACTIVE_POWER, variantStoreRow);
        double fixedReactivePower0 = variantStore.getDouble(0, COL_FIXED_REACTIVE_POWER, variantStoreRow);
        double variableActivePower0 = variantStore.getDouble(0, COL_VARIABLE_ACTIVE_POWER, variantStoreRow);
        double variableReactivePower0 = variantStore.getDouble(0, COL_VARIABLE_REACTIVE_POWER, variantStoreRow);
        this.variantStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {fixedActivePower0, fixedReactivePower0, variableActivePower0, variableReactivePower0}, INT_DEFAULTS, BOOLEAN_DEFAULTS);
    }
}
