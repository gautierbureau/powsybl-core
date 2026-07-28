/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.*;
import com.powsybl.math.graph.TraversalType;

import java.util.Objects;
import java.util.Set;

/**
 * @author Damien Jeandemange {@literal <damien.jeandemange at artelys.com>}
 */
public class DcTerminalImpl implements DcTerminal, MultiVariantObject {

    private final Ref<? extends VariantManagerHolder> network;
    private DcConnectable<?> dcConnectable;
    private final TwoSides side;
    private final TerminalNumber terminalNumber;
    private final DcNode dcNode;
    private boolean removed = false;

    // p, i (double) + connected (boolean) held columnarly
    private static final String STORE_KEY = "DcTerminal";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_P = 0;
    private static final int COL_I = 1;
    private static final int COL_CONNECTED = 0;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    DcTerminalImpl(Ref<? extends VariantManagerHolder> network, TwoSides side, TerminalNumber terminalNumber, DcNode dcNode, boolean connected) {
        if (side != null && terminalNumber != null) {
            throw new IllegalStateException("cannot have both side and number");
        }
        this.network = Objects.requireNonNull(network);
        this.side = side;
        this.terminalNumber = terminalNumber;
        this.dcNode = Objects.requireNonNull(dcNode);
        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {connected});
    }

    protected VariantManagerHolder getVariantManagerHolder() {
        return network.get();
    }

    @Override
    public DcConnectable getDcConnectable() {
        ValidationUtil.checkAccessOfRemovedEquipment(dcConnectable.getId(), this.removed);
        return this.dcConnectable;
    }

    @Override
    public TwoSides getSide() {
        ValidationUtil.checkAccessOfRemovedEquipment(dcConnectable.getId(), this.removed);
        return this.side;
    }

    @Override
    public TerminalNumber getTerminalNumber() {
        ValidationUtil.checkAccessOfRemovedEquipment(dcConnectable.getId(), this.removed);
        return this.terminalNumber;
    }

    @Override
    public DcNode getDcNode() {
        ValidationUtil.checkAccessOfRemovedEquipment(dcConnectable.getId(), this.removed);
        return this.dcNode;
    }

    @Override
    public double getP() {
        ValidationUtil.checkAccessOfRemovedEquipment(dcConnectable.getId(), this.removed);
        return variantStore.getDouble(getVariantManagerHolder().getVariantIndex(), COL_P, variantStoreRow);
    }

    @Override
    public DcTerminal setP(double p) {
        ValidationUtil.checkModifyOfRemovedEquipment(dcConnectable.getId(), this.removed);
        int variantIndex = getVariantManagerHolder().getVariantIndex();
        double oldValue = variantStore.setDouble(variantIndex, COL_P, variantStoreRow, p);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        getNetwork().getListeners().notifyUpdate(dcConnectable, () -> "p_dc" + getAttributeSideOrNumberSuffix(), variantId, oldValue, p);
        return this;
    }

    @Override
    public double getI() {
        ValidationUtil.checkAccessOfRemovedEquipment(dcConnectable.getId(), this.removed);
        return variantStore.getDouble(getVariantManagerHolder().getVariantIndex(), COL_I, variantStoreRow);
    }

    @Override
    public DcTerminal setI(double i) {
        ValidationUtil.checkModifyOfRemovedEquipment(dcConnectable.getId(), this.removed);
        int variantIndex = getVariantManagerHolder().getVariantIndex();
        double oldValue = variantStore.setDouble(variantIndex, COL_I, variantStoreRow, i);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        getNetwork().getListeners().notifyUpdate(dcConnectable, () -> "i_dc" + getAttributeSideOrNumberSuffix(), variantId, oldValue, i);
        return this;
    }

    @Override
    public boolean isConnected() {
        ValidationUtil.checkAccessOfRemovedEquipment(dcConnectable.getId(), this.removed);
        return variantStore.getBoolean(getVariantManagerHolder().getVariantIndex(), COL_CONNECTED, variantStoreRow);
    }

    @Override
    public DcTerminal setConnected(boolean connected) {
        ValidationUtil.checkModifyOfRemovedEquipment(dcConnectable.getId(), this.removed);
        int variantIndex = getVariantManagerHolder().getVariantIndex();
        boolean oldValue = variantStore.setBoolean(variantIndex, COL_CONNECTED, variantStoreRow, connected);
        if (oldValue != connected) {
            ((AbstractNetwork) dcConnectable.getParentNetwork()).getDcTopologyModel().invalidateCache();
            String variantId = network.get().getVariantManager().getVariantId(variantIndex);
            getNetwork().getListeners().notifyUpdate(dcConnectable, () -> "connected_dc" + getAttributeSideOrNumberSuffix(), variantId, oldValue, connected);
        }
        return this;
    }

    @Override
    public DcBus getDcBus() {
        return isConnected() ? dcNode.getDcBus() : null;
    }

    public void remove() {
        if (!this.removed) {
            this.removed = true;
            variantStore.freeRow(variantStoreRow);
        }
    }

    // p/i/connected maintained columnarly by the network-level store, driven once per variant op by NetworkImpl
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
        double p0 = variantStore.getDouble(0, COL_P, variantStoreRow);
        double i0 = variantStore.getDouble(0, COL_I, variantStoreRow);
        boolean connected0 = variantStore.getBoolean(0, COL_CONNECTED, variantStoreRow);
        this.variantStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {p0, i0}, INT_DEFAULTS, new boolean[] {connected0});
    }

    <I extends DcConnectable<I>> void setDcConnectable(DcConnectable<I> dcConnectable) {
        this.dcConnectable = dcConnectable;
    }

    NetworkImpl getNetwork() {
        if (dcConnectable instanceof AbstractDcConnectable<?> abstractDcConnectable) {
            return abstractDcConnectable.getNetwork();
        } else if (dcConnectable instanceof AbstractAcDcConverter<?> abstractDcConverter) {
            return abstractDcConverter.getNetwork();
        }
        throw new IllegalStateException("Unexpected dcConnectable type: " + dcConnectable.getClass().getName());
    }

    Network getParentNetwork() {
        if (dcConnectable instanceof AbstractDcConnectable<?> abstractDcConnectable) {
            return abstractDcConnectable.getParentNetwork();
        } else if (dcConnectable instanceof AbstractAcDcConverter<?> abstractDcConverter) {
            return abstractDcConverter.getParentNetwork();
        }
        throw new IllegalStateException("Unexpected dcConnectable type: " + dcConnectable.getClass().getName());
    }

    String getAttributeSideOrNumberSuffix() {
        return "" + (side != null ? side.getNum() : "") + (terminalNumber != null ? terminalNumber.getNum() : "");
    }

    public boolean traverse(TopologyTraverser traverser, Set<DcTerminal> visitedDcTerminals, TraversalType traversalType) {
        if (removed) {
            throw new PowsyblException(String.format("Associated equipment %s is removed", dcConnectable.getId()));
        }
        return getTopologyModel().traverse(this, traverser, visitedDcTerminals, traversalType);
    }

    @Override
    public void traverse(DcTerminal.TopologyTraverser traverser) {
        traverse(traverser, TraversalType.DEPTH_FIRST);
    }

    @Override
    public void traverse(DcTerminal.TopologyTraverser traverser, TraversalType traversalType) {
        if (removed) {
            throw new PowsyblException(String.format("Associated equipment %s is removed", dcConnectable.getId()));
        }
        getTopologyModel().traverse(this, traverser, traversalType);
    }

    private DcTopologyModel getTopologyModel() {
        return ((AbstractNetwork) this.getParentNetwork()).getDcTopologyModel();
    }

    @Override
    public boolean disconnect() {
        return getTopologyModel().disconnect(this);
    }
}
