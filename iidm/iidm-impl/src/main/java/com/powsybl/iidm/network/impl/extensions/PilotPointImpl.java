/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.extensions.PilotPoint;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;
import com.powsybl.iidm.network.impl.VariantManagerHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class PilotPointImpl implements PilotPoint {

    private static final String STORE_KEY = "PilotPoint";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_TARGET_V = 0;

    private final List<String> busbarSectionsOrBusesIds;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    private ControlZoneImpl controlZone;

    PilotPointImpl(List<String> busbarSectionsOrBusesIds, double targetV, VariantManagerHolder variantManagerHolder) {
        this.busbarSectionsOrBusesIds = new ArrayList<>(Objects.requireNonNull(busbarSectionsOrBusesIds));
        this.variantStore = variantManagerHolder.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {targetV}, INT_DEFAULTS, BOOLEAN_DEFAULTS);
    }

    public void setControlZone(ControlZoneImpl controlZone) {
        this.controlZone = Objects.requireNonNull(controlZone);
    }

    protected int getVariantIndex() {
        return controlZone.getSecondaryVoltageControl().getVariantManagerHolder().getVariantIndex();
    }

    /**
     * Get pilot point busbar section ID or bus ID of the bus/breaker view.
     */
    @Override
    public List<String> getBusbarSectionsOrBusesIds() {
        return Collections.unmodifiableList(busbarSectionsOrBusesIds);
    }

    protected void updateBusbarSectionsOrBusesIds(UnaryOperator<String> updater) {
        busbarSectionsOrBusesIds.replaceAll(updater);
    }

    protected void removeBusbarSectionsOrBusesIdIf(Predicate<String> predicate) {
        busbarSectionsOrBusesIds.removeIf(predicate);
    }

    @Override
    public double getTargetV() {
        return variantStore.getDouble(getVariantIndex(), COL_TARGET_V, variantStoreRow);
    }

    @Override
    public void setTargetV(double targetV) {
        if (Double.isNaN(targetV)) {
            throw new PowsyblException("Invalid pilot point target voltage for zone '" + controlZone.getName() + "'");
        }
        int variantIndex = getVariantIndex();
        double oldTargetV = variantStore.getDouble(variantIndex, COL_TARGET_V, variantStoreRow);
        if (targetV != oldTargetV) {
            variantStore.setDouble(variantIndex, COL_TARGET_V, variantStoreRow, targetV);
            SecondaryVoltageControlImpl secondaryVoltageControl = controlZone.getSecondaryVoltageControl();
            NetworkImpl network = (NetworkImpl) secondaryVoltageControl.getExtendable();
            String variantId = network.getVariantManager().getVariantId(variantIndex);
            network.getListeners().notifyExtensionUpdate(secondaryVoltageControl, "pilotPointTargetV", variantId,
                    new TargetVoltageEvent(controlZone.getName(), oldTargetV), new TargetVoltageEvent(controlZone.getName(), targetV));
        }
    }

    void extendVariantArraySize(int number, int sourceIndex) {
        // handled by NumericVariantStore
    }

    void reduceVariantArraySize(int number) {
        // handled by NumericVariantStore
    }

    void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        // handled by NumericVariantStore
    }

    void reHomeVariantStores(NetworkImpl targetNetwork) {
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
    }
}
