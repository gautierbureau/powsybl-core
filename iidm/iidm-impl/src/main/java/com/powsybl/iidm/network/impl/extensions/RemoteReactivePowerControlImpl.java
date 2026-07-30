/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.extensions.RemoteReactivePowerControl;
import com.powsybl.iidm.network.impl.AbstractMultiVariantIdentifiableExtension;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;
import com.powsybl.iidm.network.impl.TerminalExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * @author Bertrand Rix {@literal <bertrand.rix at artelys.com>}
 */
public class RemoteReactivePowerControlImpl extends AbstractMultiVariantIdentifiableExtension<Generator> implements RemoteReactivePowerControl {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteReactivePowerControlImpl.class);

    // targetQ (double) + enabled (boolean), held columnarly
    private static final String STORE_KEY = "RemoteReactivePowerControl";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_TARGET_Q = 0;
    private static final int COL_ENABLED = 0;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    private Terminal regulatingTerminal;

    public RemoteReactivePowerControlImpl(Generator generator, double targetQ, Terminal regulatingTerminal, boolean enabled) {
        super(generator);
        this.regulatingTerminal = Objects.requireNonNull(regulatingTerminal);
        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {targetQ}, INT_DEFAULTS, new boolean[] {enabled});
        if (regulatingTerminal.getVoltageLevel().getParentNetwork() != getExtendable().getParentNetwork()) {
            throw new PowsyblException("Regulating terminal is not in the right Network ("
                    + regulatingTerminal.getVoltageLevel().getParentNetwork().getId() + " instead of "
                    + getExtendable().getParentNetwork().getId() + ")");
        }
        ((TerminalExt) regulatingTerminal).getReferrerManager().register(this);
    }

    @Override
    public double getTargetQ() {
        return variantStore.getDouble(getVariantIndex(), COL_TARGET_Q, variantStoreRow);
    }

    @Override
    public RemoteReactivePowerControl setTargetQ(double targetQ) {
        variantStore.setDouble(getVariantIndex(), COL_TARGET_Q, variantStoreRow, targetQ);
        return this;
    }

    @Override
    public RemoteReactivePowerControl setEnabled(boolean enabled) {
        variantStore.setBoolean(getVariantIndex(), COL_ENABLED, variantStoreRow, enabled);
        return this;
    }

    @Override
    public Terminal getRegulatingTerminal() {
        return regulatingTerminal;
    }

    @Override
    public RemoteReactivePowerControl setRegulatingTerminal(Terminal regulatingTerminal) {
        Objects.requireNonNull(regulatingTerminal);
        if (this.regulatingTerminal != regulatingTerminal) {
            ((TerminalExt) this.regulatingTerminal).getReferrerManager().unregister(this);
            checkRegulatingTerminal(regulatingTerminal, getExtendable().getTerminal().getVoltageLevel().getNetwork());
            this.regulatingTerminal = regulatingTerminal;
            ((TerminalExt) regulatingTerminal).getReferrerManager().register(this);
        }
        return this;
    }

    private static void checkRegulatingTerminal(Terminal regulatingTerminal, Network network) {
        if (regulatingTerminal != null && regulatingTerminal.getVoltageLevel().getNetwork() != network) {
            throw new PowsyblException("regulating terminal is not part of the same network");
        }
    }

    @Override
    public boolean isEnabled() {
        return variantStore.getBoolean(getVariantIndex(), COL_ENABLED, variantStoreRow);
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

    @Override
    public void onReferencedRemoval(Terminal removedTerminal) {
        // we cannot set regulating terminal to null because otherwise extension won't be consistent anymore
        // we cannot also as for voltage regulation fallback to a local terminal
        // so we just remove the extension
        LOGGER.warn("Remove 'RemoteReactivePowerControl' extension of generator '{}', because its regulating terminal has been removed",
                getExtendable().getId());
        getExtendable().removeExtension(RemoteReactivePowerControl.class);
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
