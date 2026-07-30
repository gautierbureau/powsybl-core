/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.LoadModel;
import com.powsybl.iidm.network.LoadType;
import com.powsybl.iidm.network.ValidationUtil;

import java.util.Optional;

/**
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class LoadImpl extends AbstractConnectable<Load> implements Load {

    private final Ref<? extends VariantManagerHolder> network;

    private LoadType loadType;

    private LoadModel model;

    // attributes depending on the variant

    // variant-dependent p0 / q0 held columnarly (see NumericVariantStore)
    private static final String STORE_KEY = "Load";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_P0 = 0;
    private static final int COL_Q0 = 1;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    LoadImpl(Ref<NetworkImpl> networkRef,
             String id, String name, boolean fictitious, LoadType loadType, LoadModel model,
             double p0, double q0) {
        super(networkRef, id, name, fictitious);
        this.network = networkRef;
        this.loadType = loadType;
        this.model = model;
        this.variantStore = networkRef.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {p0, q0}, INT_DEFAULTS, BOOLEAN_DEFAULTS);
    }

    @Override
    public TerminalExt getTerminal() {
        return terminals.get(0);
    }

    @Override
    protected String getTypeDescription() {
        return "Load";
    }

    @Override
    public LoadType getLoadType() {
        return loadType;
    }

    @Override
    public Load setLoadType(LoadType loadType) {
        ValidationUtil.checkLoadType(this, loadType);
        LoadType oldValue = this.loadType;
        this.loadType = loadType;
        notifyUpdate("loadType", oldValue.toString(), loadType.toString());
        return this;
    }

    @Override
    public double getP0() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_P0, variantStoreRow);
    }

    @Override
    public LoadImpl setP0(double p0) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkP0(this, p0, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        int variantIndex = network.get().getVariantIndex();
        double oldValue = variantStore.setDouble(variantIndex, COL_P0, variantStoreRow, p0);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("p0", variantId, oldValue, p0);
        return this;
    }

    @Override
    public double getQ0() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_Q0, variantStoreRow);
    }

    @Override
    public LoadImpl setQ0(double q0) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkQ0(this, q0, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        int variantIndex = network.get().getVariantIndex();
        double oldValue = variantStore.setDouble(variantIndex, COL_Q0, variantStoreRow, q0);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("q0", variantId, oldValue, q0);
        return this;
    }

    @Override
    public Optional<LoadModel> getModel() {
        return Optional.ofNullable(model);
    }

    // p0/q0 are maintained columnarly by the network-level store, driven once per variant operation
    // by NetworkImpl; these hooks only cascade to super.
    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
        // p0/q0 handled columnarly by NumericVariantStore
    }

    @Override
    public void reduceVariantArraySize(int number) {
        super.reduceVariantArraySize(number);
        // p0/q0 handled columnarly by NumericVariantStore
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        super.deleteVariantArrayElement(index);
        // nothing to do
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);
        // p0/q0 handled columnarly by NumericVariantStore
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork); // terminals + extensions
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
    }

}
