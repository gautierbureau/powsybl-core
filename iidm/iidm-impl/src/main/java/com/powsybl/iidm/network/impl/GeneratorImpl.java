/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class GeneratorImpl extends AbstractConnectable<Generator> implements Generator, ReactiveLimitsOwner {

    private final Ref<? extends VariantManagerHolder> network;

    private EnergySource energySource;

    private double minP;

    private double maxP;

    private double ratedS;

    private final ReactiveLimitsHolderImpl reactiveLimits;

    private final RegulatingPoint regulatingPoint;

    // variant-dependent targetP / targetQ / targetV / equivalentLocalTargetV held columnarly (see NumericVariantStore)
    private static final String STORE_KEY = "Generator";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_TARGET_P = 0;
    private static final int COL_TARGET_Q = 1;
    private static final int COL_TARGET_V = 2;
    private static final int COL_EQ_TARGET_V = 3;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    private final boolean isCondenser;

    GeneratorImpl(Ref<NetworkImpl> network,
                  String id, String name, boolean fictitious, EnergySource energySource,
                  double minP, double maxP,
                  boolean voltageRegulatorOn, TerminalExt regulatingTerminal,
                  double targetP, double targetQ, double targetV, double equivalentLocalTargetV,
                  double ratedS, boolean isCondenser) {
        super(network, id, name, fictitious);
        this.network = network;
        this.energySource = energySource;
        this.minP = minP;
        this.maxP = maxP;
        this.reactiveLimits = new ReactiveLimitsHolderImpl(this, new MinMaxReactiveLimitsImpl(-Double.MAX_VALUE, Double.MAX_VALUE));
        this.ratedS = ratedS;
        int variantArraySize = network.get().getVariantManager().getVariantArraySize();
        regulatingPoint = new RegulatingPoint(id, this::getTerminal, network, voltageRegulatorOn, true);
        regulatingPoint.setRegulatingTerminal(regulatingTerminal);
        this.variantStore = network.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {targetP, targetQ, targetV, equivalentLocalTargetV}, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.isCondenser = isCondenser;
    }

    @Override
    public TerminalExt getTerminal() {
        return terminals.get(0);
    }

    @Override
    public EnergySource getEnergySource() {
        return energySource;
    }

    @Override
    public GeneratorImpl setEnergySource(EnergySource energySource) {
        ValidationUtil.checkEnergySource(this, energySource);
        EnergySource oldValue = this.energySource;
        this.energySource = energySource;
        notifyUpdate("energySource", oldValue.toString(), energySource.toString());
        return this;
    }

    @Override
    public double getMaxP() {
        return maxP;
    }

    @Override
    public GeneratorImpl setMaxP(double maxP) {
        ValidationUtil.checkMaxP(this, maxP);
        ValidationUtil.checkActivePowerLimits(this, minP, maxP);
        double oldValue = this.maxP;
        this.maxP = maxP;
        notifyUpdate("maxP", oldValue, maxP);
        return this;
    }

    @Override
    public double getMinP() {
        return minP;
    }

    @Override
    public GeneratorImpl setMinP(double minP) {
        ValidationUtil.checkMinP(this, minP);
        ValidationUtil.checkActivePowerLimits(this, minP, maxP);
        double oldValue = this.minP;
        this.minP = minP;
        notifyUpdate("minP", oldValue, minP);
        return this;
    }

    @Override
    public boolean isVoltageRegulatorOn() {
        return regulatingPoint.isRegulating(network.get().getVariantIndex());
    }

    @Override
    public GeneratorImpl setVoltageRegulatorOn(boolean voltageRegulatorOn) {
        NetworkImpl n = getNetwork();
        int variantIndex = network.get().getVariantIndex();
        ValidationUtil.checkVoltageControl(this,
                voltageRegulatorOn, variantStore.getDouble(variantIndex, COL_TARGET_V, variantStoreRow), variantStore.getDouble(variantIndex, COL_TARGET_Q, variantStoreRow),
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        boolean oldValue = regulatingPoint.setRegulating(variantIndex, voltageRegulatorOn);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("voltageRegulatorOn", variantId, oldValue, voltageRegulatorOn);
        return this;
    }

    @Override
    public TerminalExt getRegulatingTerminal() {
        return regulatingPoint.getRegulatingTerminal();
    }

    @Override
    public GeneratorImpl setRegulatingTerminal(Terminal regulatingTerminal) {
        ValidationUtil.checkRegulatingTerminal(this, regulatingTerminal, getNetwork());
        Terminal oldValue = regulatingPoint.getRegulatingTerminal();
        regulatingPoint.setRegulatingTerminal((TerminalExt) regulatingTerminal);
        notifyUpdate("regulatingTerminal", oldValue, regulatingTerminal);
        return this;
    }

    @Override
    public double getTargetP() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_TARGET_P, variantStoreRow);
    }

    @Override
    public GeneratorImpl setTargetP(double targetP) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkActivePowerSetpoint(this, targetP, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        int variantIndex = network.get().getVariantIndex();
        double oldValue = variantStore.setDouble(network.get().getVariantIndex(), COL_TARGET_P, variantStoreRow, targetP);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("targetP", variantId, oldValue, targetP);
        return this;
    }

    @Override
    public double getTargetQ() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_TARGET_Q, variantStoreRow);
    }

    @Override
    public GeneratorImpl setTargetQ(double targetQ) {
        NetworkImpl n = getNetwork();
        int variantIndex = network.get().getVariantIndex();
        ValidationUtil.checkVoltageControl(this, regulatingPoint.isRegulating(variantIndex),
                variantStore.getDouble(variantIndex, COL_TARGET_V, variantStoreRow), targetQ, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        double oldValue = variantStore.setDouble(variantIndex, COL_TARGET_Q, variantStoreRow, targetQ);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("targetQ", variantId, oldValue, targetQ);
        return this;
    }

    @Override
    public double getTargetV() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_TARGET_V, variantStoreRow);
    }

    @Override
    public GeneratorImpl setTargetV(double targetV) {
        return this.setTargetV(targetV, Double.NaN);
    }

    @Override
    public GeneratorImpl setTargetV(double targetV, double equivalentLocalTargetV) {
        NetworkImpl n = getNetwork();
        int variantIndex = network.get().getVariantIndex();
        ValidationUtil.checkVoltageControl(this, regulatingPoint.isRegulating(variantIndex),
            targetV, variantStore.getDouble(variantIndex, COL_TARGET_Q, variantStoreRow), n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        double oldValueTargetV = variantStore.setDouble(variantIndex, COL_TARGET_V, variantStoreRow, targetV);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("targetV", variantId, oldValueTargetV, targetV);

        ValidationUtil.checkEquivalentLocalTargetV(this, equivalentLocalTargetV);
        double oldEquivalentLocalTargetV = variantStore.setDouble(variantIndex, COL_EQ_TARGET_V, variantStoreRow, equivalentLocalTargetV);
        notifyUpdate("equivalentLocalTargetV", variantId, oldEquivalentLocalTargetV, equivalentLocalTargetV);
        return this;
    }

    @Override
    public double getEquivalentLocalTargetV() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_EQ_TARGET_V, variantStoreRow);
    }

    @Override
    public double getRatedS() {
        return ratedS;
    }

    @Override
    public GeneratorImpl setRatedS(double ratedS) {
        ValidationUtil.checkRatedS(this, ratedS);
        double oldValue = this.ratedS;
        this.ratedS = ratedS;
        notifyUpdate("ratedS", oldValue, ratedS);
        return this;
    }

    @Override
    public boolean isCondenser() {
        return isCondenser;
    }

    @Override
    public ReactiveCapabilityCurveAdderImpl newReactiveCapabilityCurve() {
        return new ReactiveCapabilityCurveAdderImpl(this);
    }

    @Override
    public ReactiveCapabilityShapeAdderImpl newReactiveCapabilityShape() {
        return new ReactiveCapabilityShapeAdderImpl(this);
    }

    @Override
    public MinMaxReactiveLimitsAdderImpl newMinMaxReactiveLimits() {
        return new MinMaxReactiveLimitsAdderImpl(this);
    }

    @Override
    public ReactiveLimits getReactiveLimits() {
        return reactiveLimits.getReactiveLimits();
    }

    @Override
    public void setReactiveLimits(ReactiveLimits reactiveLimits) {
        this.reactiveLimits.setReactiveLimits(reactiveLimits);
    }

    @Override
    public <R extends ReactiveLimits> R getReactiveLimits(Class<R> type) {
        return reactiveLimits.getReactiveLimits(type);
    }

    @Override
    public void remove() {
        regulatingPoint.remove();
        super.remove();
    }

    // targetP/targetQ/targetV/equivalentLocalTargetV are maintained columnarly by the network-level store,
    // driven once per variant operation by NetworkImpl; these hooks only cascade to super and the sub-objects.
    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
        regulatingPoint.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
    }

    @Override
    public void reduceVariantArraySize(int number) {
        super.reduceVariantArraySize(number);
        regulatingPoint.reduceVariantArraySize(number);
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        super.deleteVariantArrayElement(index);
        regulatingPoint.deleteVariantArrayElement(index);
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);
        regulatingPoint.allocateVariantArrayElement(indexes, sourceIndex);
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork); // terminals + extensions
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
        regulatingPoint.reHomeVariantStores(targetNetwork);
    }

    @Override
    protected String getTypeDescription() {
        return "Generator";
    }

}
