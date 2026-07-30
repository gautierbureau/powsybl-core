/**
 * Copyright (c) 2023-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

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

    // regulating (boolean) and regulationMode (int) held columnarly; a given regulating point uses one or both
    // columns depending on the constructor (flags below), matching the former nullable trove fields.
    private static final String STORE_KEY = "RegulatingPoint";
    private static final double[] DOUBLE_DEFAULTS = {};
    private static final int[] INT_DEFAULTS = {-1};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
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
        this.variantStore = networkRef.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(DOUBLE_DEFAULTS, new int[] {regulationMode}, new boolean[] {regulating});
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
        return variantStore.setBoolean(index, COL_REGULATING, variantStoreRow, regulating);
    }

    boolean isRegulating(int index) {
        return variantStore.getBoolean(index, COL_REGULATING, variantStoreRow);
    }

    int setRegulationMode(int index, int regulationMode) {
        return variantStore.setInt(index, COL_REGULATION_MODE, variantStoreRow, regulationMode);
    }

    int getRegulationMode(int index) {
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
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
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
