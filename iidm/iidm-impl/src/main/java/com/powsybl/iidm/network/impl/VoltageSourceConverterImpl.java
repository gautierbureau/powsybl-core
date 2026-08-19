/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.ReactiveLimits;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.ValidationUtil;
import com.powsybl.iidm.network.VoltageSourceConverter;

/**
 * @author Damien Jeandemange {@literal <damien.jeandemange at artelys.com>}
 */
public class VoltageSourceConverterImpl extends AbstractAcDcConverter<VoltageSourceConverter> implements VoltageSourceConverter, ReactiveLimitsOwner {

    public static final String VOLTAGE_REGULATOR_ON_ATTRIBUTE = "voltageRegulatorOn";
    public static final String VOLTAGE_SETPOINT_ATTRIBUTE = "voltageSetpoint";
    public static final String REACTIVE_POWER_SETPOINT_ATTRIBUTE = "reactivePowerSetpoint";

    private final ReactiveLimitsHolderImpl reactiveLimits;

    // reactivePowerSetpoint, voltageSetpoint held columnarly
    private static final String STORE_KEY = "VoltageSourceConverter";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_REACTIVE_POWER_SETPOINT = 0;
    private static final int COL_VOLTAGE_SETPOINT = 1;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    private final RegulatingPoint regulatingPoint;

    VoltageSourceConverterImpl(Ref<NetworkImpl> ref, String id, String name, boolean fictitious,
                               double minP, double maxP,
                               double idleLoss, double switchingLoss, double resistiveLoss,
                               TerminalExt pccTerminal, ControlMode controlMode, double targetP, double targetVdc,
                               boolean voltageRegulatorOn, double reactivePowerSetpoint, double voltageSetpoint) {
        super(ref, id, name, fictitious, minP, maxP, idleLoss, switchingLoss, resistiveLoss,
                pccTerminal, controlMode, targetP, targetVdc);
        int variantArraySize = ref.get().getVariantManager().getVariantArraySize();
        this.variantStore = getNetwork().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {reactivePowerSetpoint, voltageSetpoint}, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.reactiveLimits = new ReactiveLimitsHolderImpl(this, new MinMaxReactiveLimitsImpl(-Double.MAX_VALUE, Double.MAX_VALUE));
        regulatingPoint = new RegulatingPoint(id, () -> null, ref, voltageRegulatorOn, true);
        regulatingPoint.setRegulatingTerminal(pccTerminal);
    }

    @Override
    protected String getTypeDescription() {
        return "AC/DC Voltage Source Converter";
    }

    @Override
    public VoltageSourceConverter setPccTerminal(Terminal pccTerminal) {
        super.setPccTerminal(pccTerminal);
        regulatingPoint.setRegulatingTerminal((TerminalExt) pccTerminal);
        return this;
    }

    @Override
    public boolean isVoltageRegulatorOn() {
        ValidationUtil.checkAccessOfRemovedEquipment(this.id, this.removed, VOLTAGE_REGULATOR_ON_ATTRIBUTE);
        return regulatingPoint.isRegulating(getNetwork().getVariantIndex());
    }

    @Override
    public VoltageSourceConverterImpl setVoltageRegulatorOn(boolean voltageRegulatorOn) {
        ValidationUtil.checkModifyOfRemovedEquipment(this.id, this.removed, VOLTAGE_REGULATOR_ON_ATTRIBUTE);
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        ValidationUtil.checkVoltageControl(this, voltageRegulatorOn,
                variantStore.getDouble(variantIndex, COL_VOLTAGE_SETPOINT, variantStoreRow),
                variantStore.getDouble(variantIndex, COL_REACTIVE_POWER_SETPOINT, variantStoreRow),
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        boolean oldValue = this.regulatingPoint.isRegulating(variantIndex);
        this.regulatingPoint.setRegulating(variantIndex, voltageRegulatorOn);
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate(VOLTAGE_REGULATOR_ON_ATTRIBUTE, variantId, oldValue, voltageRegulatorOn);
        return this;
    }

    @Override
    public double getVoltageSetpoint() {
        ValidationUtil.checkAccessOfRemovedEquipment(this.id, this.removed, VOLTAGE_SETPOINT_ATTRIBUTE);
        return variantStore.getDouble(getNetwork().getVariantIndex(), COL_VOLTAGE_SETPOINT, variantStoreRow);
    }

    @Override
    public VoltageSourceConverterImpl setVoltageSetpoint(double voltageSetpoint) {
        ValidationUtil.checkModifyOfRemovedEquipment(this.id, this.removed, VOLTAGE_SETPOINT_ATTRIBUTE);
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        ValidationUtil.checkVoltageControl(this, regulatingPoint.isRegulating(variantIndex), voltageSetpoint, variantStore.getDouble(variantIndex, COL_REACTIVE_POWER_SETPOINT, variantStoreRow),
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        double oldValue = variantStore.setDouble(variantIndex, COL_VOLTAGE_SETPOINT, variantStoreRow, voltageSetpoint);
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate(VOLTAGE_SETPOINT_ATTRIBUTE, variantId, oldValue, voltageSetpoint);
        return this;
    }

    @Override
    public double getReactivePowerSetpoint() {
        ValidationUtil.checkAccessOfRemovedEquipment(this.id, this.removed, REACTIVE_POWER_SETPOINT_ATTRIBUTE);
        return variantStore.getDouble(getNetwork().getVariantIndex(), COL_REACTIVE_POWER_SETPOINT, variantStoreRow);
    }

    @Override
    public VoltageSourceConverterImpl setReactivePowerSetpoint(double reactivePowerSetpoint) {
        ValidationUtil.checkModifyOfRemovedEquipment(this.id, this.removed, REACTIVE_POWER_SETPOINT_ATTRIBUTE);
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        ValidationUtil.checkVoltageControl(this, regulatingPoint.isRegulating(variantIndex), variantStore.getDouble(variantIndex, COL_VOLTAGE_SETPOINT, variantStoreRow), reactivePowerSetpoint,
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        double oldValue = variantStore.setDouble(variantIndex, COL_REACTIVE_POWER_SETPOINT, variantStoreRow, reactivePowerSetpoint);
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate(REACTIVE_POWER_SETPOINT_ATTRIBUTE, variantId, oldValue, reactivePowerSetpoint);
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
        // reactivePowerSetpoint/voltageSetpoint handled by NumericVariantStore
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
        super.reHomeVariantStores(targetNetwork); // AcDcConverter (own doubles + dc terminals) + AC terminals + extensions
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
        regulatingPoint.reHomeVariantStores(targetNetwork);
    }

    @Override
    public void remove() {
        regulatingPoint.remove();
        super.remove();
    }

    @Override
    protected VoltageSourceConverter self() {
        return this;
    }
}
