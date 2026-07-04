/**
 * Copyright (c) 2016, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.*;

import java.util.List;
import java.util.function.Predicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Mathieu Bague {@literal <mathieu.bague at rte-france.com>}
 */
class HvdcLineImpl extends AbstractIdentifiable<HvdcLine> implements HvdcLine {

    static final String TYPE_DESCRIPTION = "hvdcLine";

    private double r;

    private double nominalV;

    private double maxP;

    // attributes depending on the variant, held columnarly (see NumericVariantStore)
    private static final String STORE_KEY = "HvdcLine";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN};
    private static final int[] INT_DEFAULTS = {-1};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_ACTIVE_POWER_SETPOINT = 0;
    private static final int COL_CONVERTERS_MODE = 0;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    //

    private final Ref<NetworkImpl> networkRef;

    private AbstractHvdcConverterStation<?> converterStation1;

    private AbstractHvdcConverterStation<?> converterStation2;

    HvdcLineImpl(String id, String name, boolean fictitious, double r, double nominalV, double maxP, ConvertersMode convertersMode, double activePowerSetpoint,
                 AbstractHvdcConverterStation<?> converterStation1, AbstractHvdcConverterStation<?> converterStation2,
                 Ref<NetworkImpl> networkRef) {
        super(id, name, fictitious);
        this.r = r;
        this.nominalV = nominalV;
        this.maxP = maxP;
        this.variantStore = networkRef.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {activePowerSetpoint},
                new int[] {convertersMode != null ? convertersMode.ordinal() : -1},
                BOOLEAN_DEFAULTS);
        this.converterStation1 = attach(converterStation1);
        this.converterStation2 = attach(converterStation2);
        this.networkRef = networkRef;
    }

    private AbstractHvdcConverterStation<?> attach(AbstractHvdcConverterStation<?> converterStation) {
        converterStation.setHvdcLine(this);
        return converterStation;
    }

    protected void notifyUpdate(String attribute, Object oldValue, Object newValue) {
        getNetwork().getListeners().notifyUpdate(this, attribute, oldValue, newValue);
    }

    protected void notifyUpdate(String attribute, String variantId, Object oldValue, Object newValue) {
        getNetwork().getListeners().notifyUpdate(this, attribute, variantId, oldValue, newValue);
    }

    @Override
    public NetworkImpl getNetwork() {
        return networkRef.get();
    }

    @Override
    public Network getParentNetwork() {
        // the parent network is the network that contains both terminals of converter stations.
        Network subnetwork1 = converterStation1.getParentNetwork();
        Network subnetwork2 = converterStation2.getParentNetwork();
        if (subnetwork1 == subnetwork2) {
            return subnetwork1;
        }
        return networkRef.get();
    }

    @Override
    public ConvertersMode getConvertersMode() {
        int variantIndex = networkRef.get().getVariantIndex();
        int mode = variantStore.getInt(variantIndex, COL_CONVERTERS_MODE, variantStoreRow);
        return mode != -1 ? ConvertersMode.values()[mode] : null;
    }

    @Override
    public HvdcLineImpl setConvertersMode(ConvertersMode convertersMode) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkConvertersMode(this, convertersMode, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        int variantIndex = n.getVariantIndex();
        int oldOrdinal = variantStore.setInt(variantIndex, COL_CONVERTERS_MODE, variantStoreRow, convertersMode != null ? convertersMode.ordinal() : -1);
        ConvertersMode oldValue = oldOrdinal != -1 ? ConvertersMode.values()[oldOrdinal] : null;
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("convertersMode", variantId, oldValue, convertersMode);
        return this;
    }

    @Override
    public double getR() {
        return r;
    }

    @Override
    public HvdcLineImpl setR(double r) {
        ValidationUtil.checkR(this, r);
        double oldValue = this.r;
        this.r = r;
        notifyUpdate("r", oldValue, r);
        return this;
    }

    @Override
    public double getNominalV() {
        return nominalV;
    }

    @Override
    public HvdcLineImpl setNominalV(double nominalV) {
        ValidationUtil.checkNominalV(this, nominalV);
        double oldValue = this.nominalV;
        this.nominalV = nominalV;
        notifyUpdate("nominalV", oldValue, nominalV);
        return this;
    }

    @Override
    public double getMaxP() {
        return maxP;
    }

    @Override
    public HvdcLineImpl setMaxP(double maxP) {
        ValidationUtil.checkHvdcMaxP(this, maxP);
        double oldValue = this.maxP;
        this.maxP = maxP;
        notifyUpdate("maxP", oldValue, maxP);
        return this;
    }

    @Override
    public double getActivePowerSetpoint() {
        return variantStore.getDouble(getNetwork().getVariantIndex(), COL_ACTIVE_POWER_SETPOINT, variantStoreRow);
    }

    @Override
    public HvdcLineImpl setActivePowerSetpoint(double activePowerSetpoint) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkHvdcActivePowerSetpoint(this, activePowerSetpoint,
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        int variantIndex = n.getVariantIndex();
        double oldValue = variantStore.setDouble(variantIndex, COL_ACTIVE_POWER_SETPOINT, variantStoreRow, activePowerSetpoint);
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("activePowerSetpoint", variantId, oldValue, activePowerSetpoint);
        return this;
    }

    @Override
    public AbstractHvdcConverterStation<?> getConverterStation(TwoSides side) {
        return (side == TwoSides.ONE) ? getConverterStation1() : getConverterStation2();
    }

    @Override
    public AbstractHvdcConverterStation<?> getConverterStation1() {
        return converterStation1;
    }

    @Override
    public AbstractHvdcConverterStation<?> getConverterStation2() {
        return converterStation2;
    }

    // activePowerSetpoint / convertersMode are maintained columnarly by the network-level store,
    // driven once per variant operation by NetworkImpl; these hooks only cascade to super.
    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
    }

    @Override
    public void reduceVariantArraySize(int number) {
        super.reduceVariantArraySize(number);
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        super.deleteVariantArrayElement(index);
        // nothing to do
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork); // extensions
        double aps0 = variantStore.getDouble(0, COL_ACTIVE_POWER_SETPOINT, variantStoreRow);
        int cm0 = variantStore.getInt(0, COL_CONVERTERS_MODE, variantStoreRow);
        this.variantStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {aps0}, new int[] {cm0}, BOOLEAN_DEFAULTS);
    }

    @Override
    public void remove() {
        NetworkImpl network = getNetwork();

        network.getListeners().notifyBeforeRemoval(this);

        // Detach converter stations
        converterStation1.setHvdcLine(null);
        converterStation2.setHvdcLine(null);
        converterStation1 = null;
        converterStation2 = null;

        network.getIndex().remove(this);

        network.getListeners().notifyAfterRemoval(id);
    }

    @Override
    public boolean connectConverterStations(Predicate<Switch> isTypeSwitchToOperate, TwoSides side) {
        return ConnectDisconnectUtil.connectAllTerminals(
            this,
            getTerminalsOfConverterStations(side),
            isTypeSwitchToOperate,
            getNetwork().getReportNodeContext().getReportNode());
    }

    @Override
    public boolean disconnectConverterStations(Predicate<Switch> isSwitchOpenable, TwoSides side) {
        return ConnectDisconnectUtil.disconnectAllTerminals(
            this,
            getTerminalsOfConverterStations(side),
            isSwitchOpenable,
            getNetwork().getReportNodeContext().getReportNode());
    }

    private List<Terminal> getTerminalsOfConverterStations(TwoSides side) {
        return side == null ? List.of(getConverterStation1().getTerminal(), getConverterStation2().getTerminal()) : switch (side) {
            case ONE -> List.of(getConverterStation1().getTerminal());
            case TWO -> List.of(getConverterStation2().getTerminal());
        };
    }

    @Override
    protected String getTypeDescription() {
        return TYPE_DESCRIPTION;
    }
}
