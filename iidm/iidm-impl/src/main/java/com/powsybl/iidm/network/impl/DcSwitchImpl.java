/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.*;

import java.util.Objects;
import java.util.Optional;

/**
 * @author Damien Jeandemange {@literal <damien.jeandemange at artelys.com>}
 */
public class DcSwitchImpl extends AbstractIdentifiable<DcSwitch> implements DcSwitch, MultiVariantObject {

    public static final String OPEN_ATTRIBUTE = "open";
    public static final String R_ATTRIBUTE = "r";

    private final Ref<NetworkImpl> networkRef;
    private final Ref<SubnetworkImpl> subnetworkRef;
    private final DcSwitchKind kind;
    private final DcNode dcNode1;
    private final DcNode dcNode2;
    private boolean removed = false;
    private double r;

    // open held columnarly (boolean column)
    private static final String STORE_KEY = "DcSwitch";
    private static final double[] DOUBLE_DEFAULTS = {};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_OPEN = 0;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    DcSwitchImpl(Ref<NetworkImpl> ref,
                 Ref<SubnetworkImpl> subnetworkRef,
                 String id,
                 String name,
                 boolean fictitious,
                 DcSwitchKind kind,
                 DcNode dcNode1,
                 DcNode dcNode2,
                 boolean open,
                 double r) {
        super(id, name, fictitious);
        this.networkRef = Objects.requireNonNull(ref);
        this.subnetworkRef = subnetworkRef;
        this.kind = kind;
        this.dcNode1 = dcNode1;
        this.dcNode2 = dcNode2;

        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {open});
        this.r = r;
    }

    @Override
    public NetworkImpl getNetwork() {
        ValidationUtil.checkAccessOfRemovedEquipment(this.id, this.removed, "network");
        return networkRef.get();
    }

    @Override
    public Network getParentNetwork() {
        ValidationUtil.checkAccessOfRemovedEquipment(this.id, this.removed, "network");
        return Optional.ofNullable((Network) subnetworkRef.get()).orElse(getNetwork());
    }

    protected VariantManagerHolder getVariantManagerHolder() {
        return getNetwork();
    }

    @Override
    protected String getTypeDescription() {
        return "DC Switch";
    }

    @Override
    public DcSwitchKind getKind() {
        return this.kind;
    }

    @Override
    public DcNode getDcNode1() {
        ValidationUtil.checkAccessOfRemovedEquipment(this.id, this.removed, "dcNode1");
        return this.dcNode1;
    }

    @Override
    public DcNode getDcNode2() {
        ValidationUtil.checkAccessOfRemovedEquipment(this.id, this.removed, "dcNode2");
        return this.dcNode2;
    }

    @Override
    public boolean isOpen() {
        ValidationUtil.checkAccessOfRemovedEquipment(this.id, this.removed, OPEN_ATTRIBUTE);
        return variantStore.getBoolean(getVariantManagerHolder().getVariantIndex(), COL_OPEN, variantStoreRow);
    }

    @Override
    public DcSwitch setOpen(boolean open) {
        ValidationUtil.checkModifyOfRemovedEquipment(this.id, this.removed, OPEN_ATTRIBUTE);
        int variantIndex = getVariantManagerHolder().getVariantIndex();
        boolean oldValue = variantStore.getBoolean(variantIndex, COL_OPEN, variantStoreRow);
        if (oldValue != open) {
            variantStore.setBoolean(variantIndex, COL_OPEN, variantStoreRow, open);
            ((AbstractNetwork) getParentNetwork()).getDcTopologyModel().invalidateCache();
            String variantId = getVariantManagerHolder().getVariantManager().getVariantId(variantIndex);
            getNetwork().getListeners().notifyUpdate(this, OPEN_ATTRIBUTE, variantId, oldValue, open);
        }
        return this;
    }

    // open is maintained columnarly by the network-level store, driven once per variant operation by NetworkImpl
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
        super.deleteVariantArrayElement(index);
        // nothing to do
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        // handled by NumericVariantStore
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork); // extensions
        boolean open0 = variantStore.getBoolean(0, COL_OPEN, variantStoreRow);
        this.variantStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {open0});
    }

    @Override
    public void remove() {
        NetworkImpl network = getNetwork();

        network.getListeners().notifyBeforeRemoval(this);

        network.getIndex().remove(this);
        ((AbstractNetwork) getParentNetwork()).getDcTopologyModel().removeDcSwitch(getId());

        network.getListeners().notifyAfterRemoval(id);
        this.removed = true;
    }

    @Override
    public double getR() {
        ValidationUtil.checkAccessOfRemovedEquipment(this.id, this.removed, R_ATTRIBUTE);
        return r;
    }

    @Override
    public DcSwitch setR(double r) {
        ValidationUtil.checkModifyOfRemovedEquipment(this.id, this.removed, R_ATTRIBUTE);
        ValidationUtil.checkDoubleParamPositive(this, r, R_ATTRIBUTE);

        double oldValue = this.r;
        this.r = r;

        if ((r == 0.0) != (oldValue == 0.0)) {
            // if we change the value of r from 0 to non-zero
            // or vice versa, topology must be recomputed.
            getNetwork().dcTopologyModel.invalidateAllVariantsCache();
        }
        getNetwork().getListeners().notifyUpdate(this, R_ATTRIBUTE, oldValue, r);

        return this;
    }
}
