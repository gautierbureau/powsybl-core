/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.VoltageRegulation;
import com.powsybl.iidm.network.impl.AbstractMultiVariantIdentifiableExtension;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;
import com.powsybl.iidm.network.impl.TerminalExt;

/**
 * @author Coline Piloquet {@literal <coline.piloquet@rte-france.fr>}
 */
public class VoltageRegulationImpl extends AbstractMultiVariantIdentifiableExtension<Battery> implements VoltageRegulation {

    // voltageRegulatorOn (boolean) + targetV (double), held columnarly
    private static final String STORE_KEY = "VoltageRegulation";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_TARGET_V = 0;
    private static final int COL_VOLTAGE_REGULATOR_ON = 0;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    private Terminal regulatingTerminal;

    public VoltageRegulationImpl(Battery battery, Terminal regulatingTerminal, Boolean voltageRegulatorOn, double targetV) {
        super(battery);
        checkRegulatingTerminal(regulatingTerminal, getNetworkFromExtendable());
        if (voltageRegulatorOn == null) {
            throw new PowsyblException("Voltage regulator status is not defined");
        }
        setRegulatingTerminal(regulatingTerminal);
        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {targetV}, INT_DEFAULTS, new boolean[] {voltageRegulatorOn});
    }

    private static void checkRegulatingTerminal(Terminal regulatingTerminal, Network network) {
        if (regulatingTerminal != null && regulatingTerminal.getVoltageLevel().getNetwork() != network) {
            throw new PowsyblException("regulating terminal is not part of the same network");
        }
    }

    @Override
    public Terminal getRegulatingTerminal() {
        return regulatingTerminal;
    }

    @Override
    public void setRegulatingTerminal(Terminal regulatingTerminal) {
        checkRegulatingTerminal(regulatingTerminal, getNetworkFromExtendable());
        Terminal newRegulatingTerminal = regulatingTerminal != null ? regulatingTerminal : getExtendable().getTerminal();
        if (newRegulatingTerminal != this.regulatingTerminal) {
            if (this.regulatingTerminal != null) {
                ((TerminalExt) this.regulatingTerminal).getReferrerManager().unregister(this);
            }
            this.regulatingTerminal = newRegulatingTerminal;
            ((TerminalExt) this.regulatingTerminal).getReferrerManager().register(this);
        }
    }

    @Override
    public boolean isVoltageRegulatorOn() {
        return variantStore.getBoolean(getVariantIndex(), COL_VOLTAGE_REGULATOR_ON, variantStoreRow);
    }

    @Override
    public void setVoltageRegulatorOn(boolean voltageRegulatorOn) {
        variantStore.setBoolean(getVariantIndex(), COL_VOLTAGE_REGULATOR_ON, variantStoreRow, voltageRegulatorOn);
    }

    @Override
    public double getTargetV() {
        return variantStore.getDouble(getVariantIndex(), COL_TARGET_V, variantStoreRow);
    }

    @Override
    public void setTargetV(double targetV) {
        variantStore.setDouble(getVariantIndex(), COL_TARGET_V, variantStoreRow, targetV);
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
        // Nothing to do
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

    private Network getNetworkFromExtendable() {
        return getExtendable().getTerminal().getVoltageLevel().getNetwork();
    }

    @Override
    public void onReferencedRemoval(Terminal removedTerminal) {
        if (regulatingTerminal == removedTerminal) {
            setRegulatingTerminal(null);
        }
    }

    @Override
    public void onReferencedReplacement(Terminal oldReferenced, Terminal newReferenced) {
        if (regulatingTerminal == oldReferenced) {
            setRegulatingTerminal(newReferenced);
        }
    }

    @Override
    public void cleanup() {
        if (regulatingTerminal != null) {
            ((TerminalExt) regulatingTerminal).getReferrerManager().unregister(this);
        }
    }
}
