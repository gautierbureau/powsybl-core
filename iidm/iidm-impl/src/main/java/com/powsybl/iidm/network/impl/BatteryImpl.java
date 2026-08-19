/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.*;

/**
 * {@inheritDoc}
 *
 * @author Ghiles Abdellah {@literal <ghiles.abdellah at rte-france.com>}
 */
public class BatteryImpl extends AbstractConnectable<Battery> implements Battery, ReactiveLimitsOwner {

    private final ReactiveLimitsHolderImpl reactiveLimits;

    // variant-dependent targetP / targetQ held columnarly (see NumericVariantStore)
    private static final String STORE_KEY = "Battery";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_TARGET_P = 0;
    private static final int COL_TARGET_Q = 1;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    private double minP;

    private double maxP;

    BatteryImpl(Ref<NetworkImpl> ref, String id, String name, boolean fictitious, double targetP, double targetQ, double minP, double maxP) {
        super(ref, id, name, fictitious);
        this.minP = minP;
        this.maxP = maxP;
        this.reactiveLimits = new ReactiveLimitsHolderImpl(this, new MinMaxReactiveLimitsImpl(-Double.MAX_VALUE, Double.MAX_VALUE));

        this.variantStore = ref.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {targetP, targetQ}, INT_DEFAULTS, BOOLEAN_DEFAULTS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getTypeDescription() {
        return "Battery";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getTargetP() {
        return variantStore.getDouble(getNetwork().getVariantIndex(), COL_TARGET_P, variantStoreRow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Battery setTargetP(double targetP) {
        NetworkImpl network = getNetwork();
        ValidationUtil.checkP0(this, targetP, network.getMinValidationLevel(), network.getReportNodeContext().getReportNode());
        int variantIndex = network.getVariantIndex();
        double oldValue = variantStore.setDouble(variantIndex, COL_TARGET_P, variantStoreRow, targetP);
        String variantId = network.getVariantManager().getVariantId(variantIndex);
        network.invalidateValidationLevel();
        notifyUpdate("targetP", variantId, oldValue, targetP);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getTargetQ() {
        return variantStore.getDouble(getNetwork().getVariantIndex(), COL_TARGET_Q, variantStoreRow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Battery setTargetQ(double targetQ) {
        NetworkImpl network = getNetwork();
        ValidationUtil.checkQ0(this, targetQ, network.getMinValidationLevel(), network.getReportNodeContext().getReportNode());
        int variantIndex = network.getVariantIndex();
        double oldValue = variantStore.setDouble(variantIndex, COL_TARGET_Q, variantStoreRow, targetQ);
        String variantId = network.getVariantManager().getVariantId(variantIndex);
        network.invalidateValidationLevel();
        notifyUpdate("targetQ", variantId, oldValue, targetQ);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMinP() {
        return minP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Battery setMinP(double minP) {
        ValidationUtil.checkMinP(this, minP);
        ValidationUtil.checkActivePowerLimits(this, minP, maxP);
        double oldValue = this.minP;
        this.minP = minP;
        notifyUpdate("minP", oldValue, minP);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMaxP() {
        return maxP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Battery setMaxP(double maxP) {
        ValidationUtil.checkMaxP(this, maxP);
        ValidationUtil.checkActivePowerLimits(this, minP, maxP);
        double oldValue = this.maxP;
        this.maxP = maxP;
        notifyUpdate("maxP", oldValue, maxP);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TerminalExt getTerminal() {
        return terminals.get(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ReactiveLimits getReactiveLimits() {
        return reactiveLimits.getReactiveLimits();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setReactiveLimits(ReactiveLimits reactiveLimits) {
        this.reactiveLimits.setReactiveLimits(reactiveLimits);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <R extends ReactiveLimits> R getReactiveLimits(Class<R> type) {
        return reactiveLimits.getReactiveLimits(type);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ReactiveCapabilityCurveAdder newReactiveCapabilityCurve() {
        return new ReactiveCapabilityCurveAdderImpl(this);
    }

    @Override
    public ReactiveCapabilityShapeAdderImpl newReactiveCapabilityShape() {
        return new ReactiveCapabilityShapeAdderImpl(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MinMaxReactiveLimitsAdder newMinMaxReactiveLimits() {
        return new MinMaxReactiveLimitsAdderImpl(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
        // targetP/targetQ handled columnarly by NumericVariantStore
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reduceVariantArraySize(int number) {
        super.reduceVariantArraySize(number);
        // targetP/targetQ handled columnarly by NumericVariantStore
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);
        // targetP/targetQ handled columnarly by NumericVariantStore
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork); // terminals + extensions
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
    }
}
