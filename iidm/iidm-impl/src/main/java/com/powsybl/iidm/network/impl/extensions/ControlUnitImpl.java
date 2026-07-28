/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.iidm.network.extensions.ControlUnit;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;
import com.powsybl.iidm.network.impl.VariantManagerHolder;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class ControlUnitImpl implements ControlUnit {

    private static final String STORE_KEY = "ControlUnit";
    private static final double[] DOUBLE_DEFAULTS = {};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_PARTICIPATE = 0;

    private String id;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    private ControlZoneImpl controlZone;

    ControlUnitImpl(String id, boolean participate, VariantManagerHolder variantManagerHolder) {
        this.id = Objects.requireNonNull(id);
        this.variantStore = variantManagerHolder.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {participate});
    }

    public void setControlZone(ControlZoneImpl controlZone) {
        this.controlZone = Objects.requireNonNull(controlZone);
    }

    protected int getVariantIndex() {
        return controlZone.getSecondaryVoltageControl().getVariantManagerHolder().getVariantIndex();
    }

    protected void setId(String newId) {
        id = Objects.requireNonNull(newId);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isParticipate() {
        return variantStore.getBoolean(getVariantIndex(), COL_PARTICIPATE, variantStoreRow);
    }

    @Override
    public void setParticipate(boolean participate) {
        int variantIndex = getVariantIndex();
        boolean oldParticipate = variantStore.getBoolean(variantIndex, COL_PARTICIPATE, variantStoreRow);
        if (participate != oldParticipate) {
            variantStore.setBoolean(variantIndex, COL_PARTICIPATE, variantStoreRow, participate);
            SecondaryVoltageControlImpl secondaryVoltageControl = controlZone.getSecondaryVoltageControl();
            NetworkImpl network = (NetworkImpl) secondaryVoltageControl.getExtendable();
            String variantId = network.getVariantManager().getVariantId(variantIndex);
            network.getListeners().notifyExtensionUpdate(secondaryVoltageControl, "controlUnitParticipate", variantId,
                    new ParticipateEvent(controlZone.getName(), id, oldParticipate), new ParticipateEvent(controlZone.getName(), id, participate));
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
        boolean participate0 = variantStore.getBoolean(0, COL_PARTICIPATE, variantStoreRow);
        this.variantStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {participate0});
    }
}
