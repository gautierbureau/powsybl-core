/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.ValidationException;
import com.powsybl.iidm.network.extensions.StandbyAutomaton;
import com.powsybl.iidm.network.impl.AbstractMultiVariantIdentifiableExtension;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;
import com.powsybl.iidm.network.impl.StaticVarCompensatorImpl;
import com.powsybl.iidm.network.util.NetworkReports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jérémy Labous {@literal <jlabous at silicom.fr>}
 */
public class StandbyAutomatonImpl extends AbstractMultiVariantIdentifiableExtension<StaticVarCompensator> implements StandbyAutomaton {

    private static final Logger LOGGER = LoggerFactory.getLogger(StandbyAutomatonImpl.class);

    // standby (boolean) + low/high voltage setpoint / low/high voltage threshold (double), held columnarly
    private static final String STORE_KEY = "StandbyAutomaton";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_LOW_VOLTAGE_SETPOINT = 0;
    private static final int COL_HIGH_VOLTAGE_SETPOINT = 1;
    private static final int COL_LOW_VOLTAGE_THRESHOLD = 2;
    private static final int COL_HIGH_VOLTAGE_THRESHOLD = 3;
    private static final int COL_STANDBY = 0;

    private double b0;
    private NumericVariantStore variantStore;
    private int variantStoreRow;

    private static double checkB0(StaticVarCompensatorImpl svc, double b0) {
        if (Double.isNaN(b0)) {
            throw new ValidationException(svc, "b0 is invalid");
        }
        return b0;
    }

    private static void checkVoltageConfig(StaticVarCompensatorImpl svc, double lowVoltageSetpoint, double highVoltageSetpoint,
                                           double lowVoltageThreshold, double highVoltageThreshold,
                                           boolean standby) {
        ReportNode reportNode = svc.getNetwork().getReportNodeContext().getReportNode();
        if (Double.isNaN(lowVoltageSetpoint)) {
            throw new ValidationException(svc, String.format("low voltage setpoint (%s) is invalid", lowVoltageSetpoint));
        }
        if (Double.isNaN(highVoltageSetpoint)) {
            throw new ValidationException(svc, String.format("high voltage setpoint (%s) is invalid", highVoltageSetpoint));
        }
        if (Double.isNaN(lowVoltageThreshold)) {
            throw new ValidationException(svc, String.format("low voltage threshold (%s) is invalid", lowVoltageThreshold));
        }
        if (Double.isNaN(highVoltageThreshold)) {
            throw new ValidationException(svc, String.format("high voltage threshold (%s) is invalid", highVoltageThreshold));
        }
        if (lowVoltageThreshold >= highVoltageThreshold) {
            if (standby) {
                throw new ValidationException(svc,
                        String.format("Inconsistent low (%s) and high (%s) voltage thresholds",
                                lowVoltageThreshold,
                                highVoltageThreshold));
            } else {
                LOGGER.warn("Inconsistent low {} and high ({}) voltage thresholds for StaticVarCompensator {}",
                        lowVoltageSetpoint, lowVoltageThreshold, svc.getId());
                NetworkReports.svcVoltageThresholdInvalid(reportNode, svc.getId(), lowVoltageThreshold, highVoltageThreshold);
            }
        }

        if (lowVoltageSetpoint < lowVoltageThreshold) {
            LOGGER.warn("Invalid low voltage setpoint {} < threshold {} for StaticVarCompensator {}",
                lowVoltageSetpoint, lowVoltageThreshold, svc.getId());
            NetworkReports.svcLowVoltageSetpointInvalid(reportNode, svc.getId(), lowVoltageSetpoint, lowVoltageThreshold);
        }

        if (highVoltageSetpoint > highVoltageThreshold) {
            LOGGER.warn("Invalid high voltage setpoint {} > threshold {} for StaticVarCompensator {}",
                highVoltageSetpoint, highVoltageThreshold, svc.getId());
            NetworkReports.svcHighVoltageSetpointInvalid(reportNode, svc.getId(), highVoltageSetpoint, highVoltageThreshold);
        }
    }

    public StandbyAutomatonImpl(StaticVarCompensatorImpl svc, double b0, boolean standby, double lowVoltageSetpoint, double highVoltageSetpoint,
                                double lowVoltageThreshold, double highVoltageThreshold) {
        super(svc);
        checkVoltageConfig(svc, lowVoltageSetpoint, highVoltageSetpoint, lowVoltageThreshold, highVoltageThreshold, standby);
        this.b0 = checkB0(svc, b0);
        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {lowVoltageSetpoint, highVoltageSetpoint, lowVoltageThreshold, highVoltageThreshold},
                INT_DEFAULTS, new boolean[] {standby});
    }

    @Override
    public boolean isStandby() {
        return variantStore.getBoolean(getVariantIndex(), COL_STANDBY, variantStoreRow);
    }

    @Override
    public StandbyAutomatonImpl setStandby(boolean standby) {
        int variantIndex = getVariantIndex();
        checkVoltageConfig((StaticVarCompensatorImpl) getExtendable(),
                variantStore.getDouble(variantIndex, COL_LOW_VOLTAGE_SETPOINT, variantStoreRow),
                variantStore.getDouble(variantIndex, COL_HIGH_VOLTAGE_SETPOINT, variantStoreRow),
                variantStore.getDouble(variantIndex, COL_LOW_VOLTAGE_THRESHOLD, variantStoreRow),
                variantStore.getDouble(variantIndex, COL_HIGH_VOLTAGE_THRESHOLD, variantStoreRow),
                standby);
        variantStore.setBoolean(variantIndex, COL_STANDBY, variantStoreRow, standby);
        return this;
    }

    @Override
    public double getB0() {
        return b0;
    }

    @Override
    public StandbyAutomatonImpl setB0(double b0) {
        this.b0 = checkB0((StaticVarCompensatorImpl) getExtendable(), b0);
        return this;
    }

    @Override
    public double getHighVoltageSetpoint() {
        return variantStore.getDouble(getVariantIndex(), COL_HIGH_VOLTAGE_SETPOINT, variantStoreRow);
    }

    @Override
    public StandbyAutomatonImpl setHighVoltageSetpoint(double highVoltageSetpoint) {
        int variantIndex = getVariantIndex();
        checkVoltageConfig((StaticVarCompensatorImpl) getExtendable(), variantStore.getDouble(variantIndex, COL_LOW_VOLTAGE_SETPOINT, variantStoreRow), highVoltageSetpoint,
            variantStore.getDouble(variantIndex, COL_LOW_VOLTAGE_THRESHOLD, variantStoreRow), variantStore.getDouble(variantIndex, COL_HIGH_VOLTAGE_THRESHOLD, variantStoreRow),
            variantStore.getBoolean(variantIndex, COL_STANDBY, variantStoreRow));
        variantStore.setDouble(variantIndex, COL_HIGH_VOLTAGE_SETPOINT, variantStoreRow, highVoltageSetpoint);
        return this;
    }

    @Override
    public double getHighVoltageThreshold() {
        return variantStore.getDouble(getVariantIndex(), COL_HIGH_VOLTAGE_THRESHOLD, variantStoreRow);
    }

    @Override
    public StandbyAutomatonImpl setHighVoltageThreshold(double highVoltageThreshold) {
        int variantIndex = getVariantIndex();
        checkVoltageConfig((StaticVarCompensatorImpl) getExtendable(), variantStore.getDouble(variantIndex, COL_LOW_VOLTAGE_SETPOINT, variantStoreRow),
            variantStore.getDouble(variantIndex, COL_HIGH_VOLTAGE_SETPOINT, variantStoreRow),
            variantStore.getDouble(variantIndex, COL_LOW_VOLTAGE_THRESHOLD, variantStoreRow), highVoltageThreshold,
            variantStore.getBoolean(variantIndex, COL_STANDBY, variantStoreRow));
        variantStore.setDouble(variantIndex, COL_HIGH_VOLTAGE_THRESHOLD, variantStoreRow, highVoltageThreshold);
        return this;
    }

    @Override
    public double getLowVoltageSetpoint() {
        return variantStore.getDouble(getVariantIndex(), COL_LOW_VOLTAGE_SETPOINT, variantStoreRow);
    }

    @Override
    public StandbyAutomatonImpl setLowVoltageSetpoint(double lowVoltageSetpoint) {
        int variantIndex = getVariantIndex();
        checkVoltageConfig((StaticVarCompensatorImpl) getExtendable(), lowVoltageSetpoint, variantStore.getDouble(variantIndex, COL_HIGH_VOLTAGE_SETPOINT, variantStoreRow),
            variantStore.getDouble(variantIndex, COL_LOW_VOLTAGE_THRESHOLD, variantStoreRow), variantStore.getDouble(variantIndex, COL_HIGH_VOLTAGE_THRESHOLD, variantStoreRow),
            variantStore.getBoolean(variantIndex, COL_STANDBY, variantStoreRow));
        variantStore.setDouble(variantIndex, COL_LOW_VOLTAGE_SETPOINT, variantStoreRow, lowVoltageSetpoint);
        return this;
    }

    @Override
    public double getLowVoltageThreshold() {
        return variantStore.getDouble(getVariantIndex(), COL_LOW_VOLTAGE_THRESHOLD, variantStoreRow);
    }

    @Override
    public StandbyAutomatonImpl setLowVoltageThreshold(double lowVoltageThreshold) {
        int variantIndex = getVariantIndex();
        checkVoltageConfig((StaticVarCompensatorImpl) getExtendable(), variantStore.getDouble(variantIndex, COL_LOW_VOLTAGE_SETPOINT, variantStoreRow),
            variantStore.getDouble(variantIndex, COL_HIGH_VOLTAGE_SETPOINT, variantStoreRow),
            lowVoltageThreshold, variantStore.getDouble(variantIndex, COL_HIGH_VOLTAGE_THRESHOLD, variantStoreRow),
            variantStore.getBoolean(variantIndex, COL_STANDBY, variantStoreRow));
        variantStore.setDouble(variantIndex, COL_LOW_VOLTAGE_THRESHOLD, variantStoreRow, lowVoltageThreshold);
        return this;
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
    public void deleteVariantArrayElement(int i) {
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
