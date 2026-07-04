/**
 * Copyright (c) 2016, RTE (http://www.rte-france.com)
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
 * @author Mathieu Bague {@literal <mathieu.bague at rte-france.com>}
 */
class VscConverterStationImpl extends AbstractHvdcConverterStation<VscConverterStation> implements VscConverterStation, ReactiveLimitsOwner {

    static final String TYPE_DESCRIPTION = "vscConverterStation";

    private final ReactiveLimitsHolderImpl reactiveLimits;

    // variant-dependent reactivePowerSetpoint / voltageSetpoint held columnarly (see NumericVariantStore)
    private static final String STORE_KEY = "VscConverterStation";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_REACTIVE_POWER_SETPOINT = 0;
    private static final int COL_VOLTAGE_SETPOINT = 1;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    private final RegulatingPoint regulatingPoint;

    VscConverterStationImpl(String id, String name, boolean fictitious, float lossFactor, Ref<NetworkImpl> ref,
                            boolean voltageRegulatorOn, double reactivePowerSetpoint, double voltageSetpoint, TerminalExt regulatingTerminal) {
        super(ref, id, name, fictitious, lossFactor);
        int variantArraySize = ref.get().getVariantManager().getVariantArraySize();
        this.variantStore = ref.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {reactivePowerSetpoint, voltageSetpoint}, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.reactiveLimits = new ReactiveLimitsHolderImpl(this, new MinMaxReactiveLimitsImpl(-Double.MAX_VALUE, Double.MAX_VALUE));
        regulatingPoint = new RegulatingPoint(id, this::getTerminal, ref, voltageRegulatorOn, true);
        regulatingPoint.setRegulatingTerminal(regulatingTerminal);
    }

    @Override
    public HvdcType getHvdcType() {
        return HvdcType.VSC;
    }

    @Override
    protected String getTypeDescription() {
        return TYPE_DESCRIPTION;
    }

    @Override
    public boolean isVoltageRegulatorOn() {
        return regulatingPoint.isRegulating(getNetwork().getVariantIndex());
    }

    @Override
    public VscConverterStationImpl setVoltageRegulatorOn(boolean voltageRegulatorOn) {
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        ValidationUtil.checkVoltageControl(this, voltageRegulatorOn,
                variantStore.getDouble(variantIndex, COL_VOLTAGE_SETPOINT, variantStoreRow), variantStore.getDouble(variantIndex, COL_REACTIVE_POWER_SETPOINT, variantStoreRow),
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        boolean oldValue = this.regulatingPoint.isRegulating(variantIndex);
        this.regulatingPoint.setRegulating(variantIndex, voltageRegulatorOn);
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("voltageRegulatorOn", variantId, oldValue, voltageRegulatorOn);
        return this;
    }

    @Override
    public double getVoltageSetpoint() {
        return variantStore.getDouble(getNetwork().getVariantIndex(), COL_VOLTAGE_SETPOINT, variantStoreRow);
    }

    @Override
    public VscConverterStationImpl setVoltageSetpoint(double voltageSetpoint) {
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        ValidationUtil.checkVoltageControl(this, regulatingPoint.isRegulating(variantIndex), voltageSetpoint, variantStore.getDouble(variantIndex, COL_REACTIVE_POWER_SETPOINT, variantStoreRow),
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        double oldValue = variantStore.setDouble(variantIndex, COL_VOLTAGE_SETPOINT, variantStoreRow, voltageSetpoint);
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("voltageSetpoint", variantId, oldValue, voltageSetpoint);
        return this;
    }

    @Override
    public double getReactivePowerSetpoint() {
        return variantStore.getDouble(getNetwork().getVariantIndex(), COL_REACTIVE_POWER_SETPOINT, variantStoreRow);
    }

    @Override
    public VscConverterStationImpl setReactivePowerSetpoint(double reactivePowerSetpoint) {
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        ValidationUtil.checkVoltageControl(this, regulatingPoint.isRegulating(variantIndex), variantStore.getDouble(variantIndex, COL_VOLTAGE_SETPOINT, variantStoreRow), reactivePowerSetpoint,
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        double oldValue = variantStore.setDouble(variantIndex, COL_REACTIVE_POWER_SETPOINT, variantStoreRow, reactivePowerSetpoint);
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("reactivePowerSetpoint", variantId, oldValue, reactivePowerSetpoint);
        return this;
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
    public void setReactiveLimits(ReactiveLimits reactiveLimits) {
        this.reactiveLimits.setReactiveLimits(reactiveLimits);
    }

    @Override
    public ReactiveLimits getReactiveLimits() {
        return reactiveLimits.getReactiveLimits();
    }

    @Override
    public <L extends ReactiveLimits> L getReactiveLimits(Class<L> type) {
        return reactiveLimits.getReactiveLimits(type);
    }

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
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);
        regulatingPoint.allocateVariantArrayElement(indexes, sourceIndex);
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork);
        double reactivePowerSetpoint0 = variantStore.getDouble(0, COL_REACTIVE_POWER_SETPOINT, variantStoreRow);
        double voltageSetpoint0 = variantStore.getDouble(0, COL_VOLTAGE_SETPOINT, variantStoreRow);
        this.variantStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {reactivePowerSetpoint0, voltageSetpoint0}, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        regulatingPoint.reHomeVariantStores(targetNetwork);
    }

    @Override
    public TerminalExt getRegulatingTerminal() {
        return regulatingPoint.getRegulatingTerminal();
    }

    @Override
    public VscConverterStationImpl setRegulatingTerminal(Terminal regulatingTerminal) {
        ValidationUtil.checkRegulatingTerminal(this, regulatingTerminal, getNetwork());
        Terminal oldValue = regulatingPoint.getRegulatingTerminal();
        regulatingPoint.setRegulatingTerminal((TerminalExt) regulatingTerminal);
        notifyUpdate("regulatingTerminal", oldValue, regulatingTerminal);
        return this;
    }

    @Override
    public void remove() {
        regulatingPoint.remove();
        super.remove();
    }
}
