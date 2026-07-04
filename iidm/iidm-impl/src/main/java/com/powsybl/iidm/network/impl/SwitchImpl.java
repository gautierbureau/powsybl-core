/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.*;

/**
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class SwitchImpl extends AbstractIdentifiable<Switch> implements Switch, MultiVariantObject {

    private final VoltageLevelExt voltageLevel;

    private final SwitchKind kind;

    // open/retained are held columnarly in the network-level SwitchVariantStore (structure-of-arrays), so a
    // variant clone extends all switches at once with a bulk array copy instead of once per switch. This
    // switch owns a single row in that store (re-homed if the switch moves network on merge/detach).
    private int variantStoreRow;

    SwitchImpl(VoltageLevelExt voltageLevel,
               String id, String name, boolean fictitious, SwitchKind kind, final boolean open, boolean retained) {
        super(id, name, fictitious);
        this.voltageLevel = voltageLevel;
        this.kind = kind;
        this.variantStoreRow = voltageLevel.getNetwork().getSwitchVariantStore().allocateRow(open, retained);
    }

    @Override
    public NetworkImpl getNetwork() {
        return voltageLevel.getNetwork();
    }

    @Override
    public Network getParentNetwork() {
        return voltageLevel.getParentNetwork();
    }

    @Override
    public VoltageLevelExt getVoltageLevel() {
        return voltageLevel;
    }

    @Override
    public SwitchKind getKind() {
        return kind;
    }

    @Override
    public boolean isOpen() {
        NetworkImpl network = getNetwork();
        return network.getSwitchVariantStore().getOpen(network.getVariantIndex(), variantStoreRow);
    }

    @Override
    public void setOpen(boolean open) {
        NetworkImpl network = getNetwork();
        int index = network.getVariantIndex();
        SwitchVariantStore store = network.getSwitchVariantStore();
        boolean oldValue = store.getOpen(index, variantStoreRow);
        if (oldValue != open) {
            store.setOpen(index, variantStoreRow, open);
            voltageLevel.getTopologyModel().invalidateCache(isRetained());
            String variantId = network.getVariantManager().getVariantId(index);
            network.getListeners().notifyUpdate(this, "open", variantId, oldValue, open);
        }
    }

    @Override
    public boolean isRetained() {
        NetworkImpl network = getNetwork();
        return network.getSwitchVariantStore().getRetained(network.getVariantIndex(), variantStoreRow);
    }

    @Override
    public void setRetained(boolean retained) {
        if (voltageLevel.getTopologyKind() != TopologyKind.NODE_BREAKER) {
            throw new ValidationException(this, "retain status is not modifiable in a non node/breaker voltage level");
        }
        NetworkImpl network = getNetwork();
        int index = network.getVariantIndex();
        SwitchVariantStore store = network.getSwitchVariantStore();
        boolean oldValue = store.getRetained(index, variantStoreRow);
        if (oldValue != retained) {
            store.setRetained(index, variantStoreRow, retained);
            voltageLevel.getTopologyModel().invalidateCache();
            String variantId = network.getVariantManager().getVariantId(index);
            network.getListeners().notifyUpdate(this, "retained", variantId, oldValue, retained);
        }
    }

    @Override
    public void setFictitious(boolean fictitious) {
        boolean oldValue = this.fictitious;
        if (oldValue != fictitious) {
            this.fictitious = fictitious;
            voltageLevel.getTopologyModel().invalidateCache();
            NetworkImpl network = getNetwork();
            network.getListeners().notifyUpdate(this, "fictitious", oldValue, fictitious);
        }
    }

    // open/retained are maintained columnarly by the network-level SwitchVariantStore, which the root network
    // extends/reduces/allocates once per variant operation (see NetworkImpl). These per-switch hooks therefore
    // have nothing to do for open/retained; super still handles the (rare) switch extensions.
    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
        // open/retained handled by SwitchVariantStore
    }

    @Override
    public void reduceVariantArraySize(int number) {
        super.reduceVariantArraySize(number);
        // open/retained handled by SwitchVariantStore
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        super.deleteVariantArrayElement(index);
        // nothing to do
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, final int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);
        // open/retained handled by SwitchVariantStore
    }

    /**
     * Move this switch's open/retained into {@code newStore}, allocating a fresh row there. Called when the
     * switch changes network (merge/detach), before the network reference is redirected. Only single-variant
     * networks can be merged/detached, so only the initial variant is transferred.
     */
    void reHomeVariantStores(NetworkImpl targetNetwork) {
        SwitchVariantStore oldStore = getNetwork().getSwitchVariantStore();
        boolean open0 = oldStore.getOpen(0, variantStoreRow);
        boolean retained0 = oldStore.getRetained(0, variantStoreRow);
        this.variantStoreRow = targetNetwork.getSwitchVariantStore().importRow(open0, retained0);
    }

    @Override
    protected String getTypeDescription() {
        return "Switch";
    }
}
