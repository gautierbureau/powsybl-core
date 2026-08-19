/**
 * Copyright (c) 2023-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * @author Miora Vedelago {@literal <miora.ralambotiana at rte-france.com>}
 */
class RegulatingPoint implements MultiVariantObject, Referrer<Terminal> {

    private static final Logger LOG = LoggerFactory.getLogger(RegulatingPoint.class);

    // regulating (boolean) and regulationMode (int) are held columnarly. A regulating point carries one or
    // both, depending on the constructor: a generator or tap changer has a regulating flag and no mode, an
    // AC/DC converter has a mode and no regulating flag, a static var compensator has both. That is what the
    // nullable trove fields used to express, and each kind gets its own store so that a point still only pays
    // for the columns it actually has.
    private static final String STORE_KEY_REGULATING = "RegulatingPoint.regulating";
    private static final String STORE_KEY_MODE = "RegulatingPoint.mode";
    private static final String STORE_KEY_REGULATING_AND_MODE = "RegulatingPoint.regulatingAndMode";
    private static final double[] NO_DOUBLES = {};
    private static final int[] NO_INTS = {};
    private static final boolean[] NO_BOOLEANS = {};
    private static final int[] MODE_DEFAULTS = {-1};
    private static final boolean[] REGULATING_DEFAULTS = {false};
    private static final int COL_REGULATION_MODE = 0;
    private static final int COL_REGULATING = 0;

    private final String regulatedEquipmentId;
    private final Supplier<TerminalExt> localTerminalSupplier;
    private final boolean useVoltageRegulation;
    private final int offRegulationMode; // mode to be used on regulating terminal removal
    private TerminalExt regulatingTerminal;

    // attributes depending on the variant
    private final boolean hasRegulating;
    private final boolean hasRegulationMode;
    private NumericVariantStore variantStore;
    private int variantStoreRow;

    RegulatingPoint(String regulatedEquipmentId, Supplier<TerminalExt> localTerminalSupplier, Ref<? extends VariantManagerHolder> networkRef, boolean regulating, boolean useVoltageRegulation) {
        this.regulatedEquipmentId = regulatedEquipmentId;
        this.localTerminalSupplier = localTerminalSupplier;
        this.useVoltageRegulation = useVoltageRegulation;
        this.hasRegulating = true;
        this.hasRegulationMode = false;
        this.offRegulationMode = -1;
        allocate(networkRef, regulating, -1);
    }

    RegulatingPoint(String regulatedEquipmentId, Supplier<TerminalExt> localTerminalSupplier, Ref<? extends VariantManagerHolder> networkRef,
                    int regulationMode, boolean regulating, int offRegulationMode, boolean useVoltageRegulation) {
        this.regulatedEquipmentId = regulatedEquipmentId;
        this.localTerminalSupplier = localTerminalSupplier;
        this.useVoltageRegulation = useVoltageRegulation;
        this.hasRegulating = true;
        this.hasRegulationMode = true;
        this.offRegulationMode = offRegulationMode;
        allocate(networkRef, regulating, regulationMode);
    }

    RegulatingPoint(String regulatedEquipmentId, Supplier<TerminalExt> localTerminalSupplier, Ref<? extends VariantManagerHolder> networkRef,
                    int regulationMode, int offRegulationMode, boolean useVoltageRegulation) {
        this.regulatedEquipmentId = regulatedEquipmentId;
        this.localTerminalSupplier = localTerminalSupplier;
        this.useVoltageRegulation = useVoltageRegulation;
        this.hasRegulating = false;
        this.hasRegulationMode = true;
        this.offRegulationMode = offRegulationMode;
        allocate(networkRef, false, regulationMode);
    }

    private void allocate(Ref<? extends VariantManagerHolder> networkRef, boolean regulating, int regulationMode) {
        this.variantStore = networkRef.get().getOrCreateNumericVariantStore(storeKey(), NO_DOUBLES, intDefaults(), booleanDefaults());
        this.variantStoreRow = variantStore.allocateRow(NO_DOUBLES,
                hasRegulationMode ? new int[] {regulationMode} : NO_INTS,
                hasRegulating ? new boolean[] {regulating} : NO_BOOLEANS);
    }

    private String storeKey() {
        if (hasRegulating) {
            return hasRegulationMode ? STORE_KEY_REGULATING_AND_MODE : STORE_KEY_REGULATING;
        }
        return STORE_KEY_MODE;
    }

    private int[] intDefaults() {
        return hasRegulationMode ? MODE_DEFAULTS : NO_INTS;
    }

    private boolean[] booleanDefaults() {
        return hasRegulating ? REGULATING_DEFAULTS : NO_BOOLEANS;
    }

    // A regulating point only answers for the attributes its constructor gave it. Asking for the other one used
    // to be a NullPointerException off a null trove list; it stays an error rather than quietly reading a
    // column that would always hold the default.
    private void checkHasRegulating() {
        if (!hasRegulating) {
            throw new PowsyblException("Regulating point of '" + regulatedEquipmentId
                    + "' has no regulating attribute, only a regulation mode");
        }
    }

    private void checkHasRegulationMode() {
        if (!hasRegulationMode) {
            throw new PowsyblException("Regulating point of '" + regulatedEquipmentId
                    + "' has no regulation mode attribute, only a regulating flag");
        }
    }

    void setRegulatingTerminal(TerminalExt regulatingTerminal) {
        if (this.regulatingTerminal != null) {
            this.regulatingTerminal.getReferrerManager().unregister(this);
            this.regulatingTerminal = null;
        }
        if (regulatingTerminal != null) {
            this.regulatingTerminal = regulatingTerminal;
            this.regulatingTerminal.getReferrerManager().register(this);
        }
    }

    TerminalExt getRegulatingTerminal() {
        return regulatingTerminal != null ? regulatingTerminal : localTerminalSupplier.get();
    }

    boolean setRegulating(int index, boolean regulating) {
        checkHasRegulating();
        return variantStore.setBoolean(index, COL_REGULATING, variantStoreRow, regulating);
    }

    boolean isRegulating(int index) {
        checkHasRegulating();
        return variantStore.getBoolean(index, COL_REGULATING, variantStoreRow);
    }

    int setRegulationMode(int index, int regulationMode) {
        checkHasRegulationMode();
        return variantStore.setInt(index, COL_REGULATION_MODE, variantStoreRow, regulationMode);
    }

    int getRegulationMode(int index) {
        checkHasRegulationMode();
        return variantStore.getInt(index, COL_REGULATION_MODE, variantStoreRow);
    }

    // regulating/regulationMode are maintained columnarly by the network-level store, driven once per variant op
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
        // nothing to do
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        // handled by NumericVariantStore
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(storeKey(), NO_DOUBLES, intDefaults(), booleanDefaults());
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
    }

    void remove() {
        if (regulatingTerminal != null) {
            regulatingTerminal.getReferrerManager().unregister(this);
        }
    }

    @Override
    public void onReferencedRemoval(Terminal removedTerminal) {
        TerminalExt oldRegulatingTerminal = regulatingTerminal;
        TerminalExt localTerminal = localTerminalSupplier.get();
        if (localTerminal != null && useVoltageRegulation) { // if local voltage regulation, we keep the regulating status, and re-locate the regulation at the regulated equipment
            Bus bus = regulatingTerminal.getBusView().getBus();
            Bus localBus = localTerminal.getBusView().getBus();
            if (bus != null && bus == localBus) {
                LOG.warn("Connectable {} was a local voltage regulation point for {}. Regulation point is re-located at {}.", regulatingTerminal.getConnectable().getId(),
                        regulatedEquipmentId, regulatedEquipmentId);
                regulatingTerminal = localTerminal;
                return;
            } else {
                regulatingTerminal = null;
            }
        } else {
            regulatingTerminal = null;
        }
        LOG.warn("Connectable {} was a regulation point for {}. Regulation is deactivated", oldRegulatingTerminal.getConnectable().getId(), regulatedEquipmentId);
        if (hasRegulating) {
            variantStore.fillBoolean(COL_REGULATING, variantStoreRow, false);
        }
        if (hasRegulationMode) {
            variantStore.fillInt(COL_REGULATION_MODE, variantStoreRow, this.offRegulationMode);
        }
    }

    @Override
    public void onReferencedReplacement(Terminal oldReferenced, Terminal newReferenced) {
        if (regulatingTerminal == oldReferenced) {
            regulatingTerminal = (TerminalExt) newReferenced;
        }
    }
}
