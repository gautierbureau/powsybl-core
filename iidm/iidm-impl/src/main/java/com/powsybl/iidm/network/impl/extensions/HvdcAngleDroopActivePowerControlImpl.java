/*
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl;
import com.powsybl.iidm.network.impl.AbstractMultiVariantIdentifiableExtension;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;

import java.util.Objects;

/**
 * Active power control mode based on an offset in MW and a droop in MW/degree
 * ActivePowerSetpoint = p0 + droop * (angle1 - angle2)
 *
 * @author Mathieu Bague {@literal <mathieu.bague at rte-france.com>}
 * @author Paul Bui-Quang {@literal <paul.buiquang at rte-france.com>}
 */
public class HvdcAngleDroopActivePowerControlImpl extends AbstractMultiVariantIdentifiableExtension<HvdcLine> implements HvdcAngleDroopActivePowerControl {

    // p0 (active power offset in MW) and droop (in MW/degree) held as double columns; enabled held as boolean column
    private static final String STORE_KEY = "HvdcAngleDroopActivePowerControl";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_P0 = 0;
    private static final int COL_DROOP = 1;
    private static final int COL_ENABLED = 0;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    public HvdcAngleDroopActivePowerControlImpl(HvdcLine hvdcLine, float p0, float droop, boolean enabled) {
        super(hvdcLine);
        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {checkP0(p0, hvdcLine), checkDroop(droop, hvdcLine)}, INT_DEFAULTS, new boolean[] {enabled});
    }

    @Override
    public float getP0() {
        return (float) variantStore.getDouble(getVariantIndex(), COL_P0, variantStoreRow);
    }

    @Override
    public float getDroop() {
        return (float) variantStore.getDouble(getVariantIndex(), COL_DROOP, variantStoreRow);
    }

    @Override
    public boolean isEnabled() {
        return variantStore.getBoolean(getVariantIndex(), COL_ENABLED, variantStoreRow);
    }

    @Override
    public HvdcAngleDroopActivePowerControl setP0(float p0) {
        variantStore.setDouble(getVariantIndex(), COL_P0, variantStoreRow, checkP0(p0, this.getExtendable()));
        return this;
    }

    @Override
    public HvdcAngleDroopActivePowerControl setDroop(float droop) {
        variantStore.setDouble(getVariantIndex(), COL_DROOP, variantStoreRow, checkDroop(droop, this.getExtendable()));
        return this;
    }

    @Override
    public HvdcAngleDroopActivePowerControl setEnabled(boolean enabled) {
        variantStore.setBoolean(getVariantIndex(), COL_ENABLED, variantStoreRow, enabled);
        return this;
    }

    private float checkP0(float p0, HvdcLine hvdcLine) {
        if (Float.isNaN(p0)) {
            throw new IllegalArgumentException(String.format("p0 value (%s) is invalid for HVDC line %s",
                p0,
                hvdcLine.getId()));
        }

        return p0;
    }

    private float checkDroop(float droop, HvdcLine hvdcLine) {
        if (Float.isNaN(droop)) {
            throw new IllegalArgumentException(String.format("droop value (%s) is invalid for HVDC line %s",
                droop,
                hvdcLine.getId()));
        }

        return droop;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HvdcAngleDroopActivePowerControlImpl that = (HvdcAngleDroopActivePowerControlImpl) o;
        return Float.compare(that.getP0(), getP0()) == 0 &&
                Float.compare(that.getDroop(), getDroop()) == 0 &&
                isEnabled() == that.isEnabled();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getP0(), getDroop(), isEnabled());
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
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        // handled by NumericVariantStore
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
    }
}
