/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Injection;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.impl.AbstractMultiVariantIdentifiableExtension;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalDouble;

/**
 * @author Ghiles Abdellah {@literal <ghiles.abdellah at rte-france.com>}
 */
public class ActivePowerControlImpl<T extends Injection<T>> extends AbstractMultiVariantIdentifiableExtension<T>
        implements ActivePowerControl<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivePowerControlImpl.class);

    // participate (boolean) + droop / participationFactor / minTargetP / maxTargetP (double), held columnarly
    private static final String STORE_KEY = "ActivePowerControl";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_DROOP = 0;
    private static final int COL_PARTICIPATION_FACTOR = 1;
    private static final int COL_MIN_TARGET_P = 2;
    private static final int COL_MAX_TARGET_P = 3;
    private static final int COL_PARTICIPATE = 0;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    public ActivePowerControlImpl(T component,
                                  boolean participate,
                                  double droop,
                                  double participationFactor) {
        this(component, participate, droop, participationFactor, Double.NaN, Double.NaN);
    }

    public ActivePowerControlImpl(T component,
                                  boolean participate,
                                  double droop,
                                  double participationFactor,
                                  double minTargetP,
                                  double maxTargetP) {
        super(component);
        double checkedMinTargetP = checkTargetPLimit(minTargetP, "minTargetP", component);
        double checkedMaxTargetP = checkTargetPLimit(maxTargetP, "maxTargetP", component);
        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {droop, participationFactor, checkedMinTargetP, checkedMaxTargetP}, INT_DEFAULTS, new boolean[] {participate});
        checkLimitOrder(minTargetP, maxTargetP);
    }

    record PLimits(double minP, double maxP) { }

    private PLimits getPLimits(T injection) {
        double maxP = Double.MAX_VALUE;
        double minP = -Double.MAX_VALUE;
        if (injection instanceof Generator generator) {
            maxP = generator.getMaxP();
            minP = generator.getMinP();
        } else if (injection instanceof Battery battery) {
            maxP = battery.getMaxP();
            minP = battery.getMinP();
        }
        return new PLimits(minP, maxP);
    }

    private double withinPMinMax(double value, T injection) {
        PLimits pLimits = getPLimits(injection);

        if (!Double.isNaN(value) && (value < pLimits.minP || value > pLimits.maxP)) {
            LOGGER.warn("targetP limit is now outside of pMin,pMax for component {}. Returning closest value in [pmin,pMax].",
                        injection.getId());
            return value < pLimits.minP ? pLimits.minP : pLimits.maxP;
        }
        return value;
    }

    private double checkTargetPLimit(double targetPLimit, String name, T injection) {
        PLimits pLimits = getPLimits(injection);

        if (!Double.isNaN(targetPLimit) && (targetPLimit < pLimits.minP || targetPLimit > pLimits.maxP)) {
            throw new PowsyblException(String.format("%s value (%s) is not between minP and maxP for component %s",
                    name,
                    targetPLimit,
                    injection.getId()));
        }

        return targetPLimit;
    }

    private void checkLimitOrder(double minTargetP, double maxTargetP) {
        if (!Double.isNaN(minTargetP) && !Double.isNaN(maxTargetP)
                && minTargetP > maxTargetP) {
            throw new PowsyblException("invalid targetP limits [" + minTargetP + ", " + maxTargetP + "]");
        }
    }

    public boolean isParticipate() {
        return variantStore.getBoolean(getVariantIndex(), COL_PARTICIPATE, variantStoreRow);
    }

    public void setParticipate(boolean participate) {
        int variantIndex = getVariantIndex();
        boolean oldParticipate = variantStore.getBoolean(variantIndex, COL_PARTICIPATE, variantStoreRow);
        if (oldParticipate != participate) {
            variantStore.setBoolean(variantIndex, COL_PARTICIPATE, variantStoreRow, participate);
            NetworkImpl network = (NetworkImpl) getExtendable().getNetwork();
            String variantId = getVariantManagerHolder().getVariantManager().getWorkingVariantId();
            network.getListeners().notifyExtensionUpdate(this, "participate", variantId, oldParticipate, participate);
        }
    }

    public double getDroop() {
        return variantStore.getDouble(getVariantIndex(), COL_DROOP, variantStoreRow);
    }

    public void setDroop(double droop) {
        int variantIndex = getVariantIndex();
        double oldDroop = variantStore.getDouble(variantIndex, COL_DROOP, variantStoreRow);
        if (oldDroop != droop) {
            variantStore.setDouble(variantIndex, COL_DROOP, variantStoreRow, droop);
            NetworkImpl network = (NetworkImpl) getExtendable().getNetwork();
            String variantId = getVariantManagerHolder().getVariantManager().getWorkingVariantId();
            network.getListeners().notifyExtensionUpdate(this, "droop", variantId, oldDroop, droop);
        }
    }

    public double getParticipationFactor() {
        return variantStore.getDouble(getVariantIndex(), COL_PARTICIPATION_FACTOR, variantStoreRow);
    }

    public void setParticipationFactor(double participationFactor) {
        variantStore.setDouble(getVariantIndex(), COL_PARTICIPATION_FACTOR, variantStoreRow, participationFactor);
    }

    // participate/droop/participationFactor/min/maxTargetP are maintained columnarly by the network-level
    // store, driven once per variant operation by NetworkImpl; these per-extension hooks have nothing to do.
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

    @Override
    public OptionalDouble getMinTargetP() {
        double result = variantStore.getDouble(getVariantIndex(), COL_MIN_TARGET_P, variantStoreRow);
        return Double.isNaN(result) ? OptionalDouble.empty() : OptionalDouble.of(withinPMinMax(result, getExtendable()));
    }

    @Override
    public void setMinTargetP(double minTargetP) {
        checkLimitOrder(minTargetP, variantStore.getDouble(getVariantIndex(), COL_MAX_TARGET_P, variantStoreRow));
        variantStore.setDouble(getVariantIndex(), COL_MIN_TARGET_P, variantStoreRow, checkTargetPLimit(minTargetP, "minTargetP", getExtendable()));
    }

    @Override
    public OptionalDouble getMaxTargetP() {
        double result = variantStore.getDouble(getVariantIndex(), COL_MAX_TARGET_P, variantStoreRow);
        return Double.isNaN(result) ? OptionalDouble.empty() : OptionalDouble.of(withinPMinMax(result, getExtendable()));
    }

    @Override
    public void setMaxTargetP(double maxTargetP) {
        checkLimitOrder(variantStore.getDouble(getVariantIndex(), COL_MIN_TARGET_P, variantStoreRow), maxTargetP);
        variantStore.setDouble(getVariantIndex(), COL_MAX_TARGET_P, variantStoreRow, checkTargetPLimit(maxTargetP, "maxTargetP", getExtendable()));
    }
}
