/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class ConfiguredBusImpl extends AbstractBus implements ConfiguredBus {

    private final Ref<NetworkImpl> network;

    // per-variant list of connected terminals: not numeric, kept per-object
    private final ArrayList<List<BusTerminal>> terminals;

    // v, angle, fictitiousP0, fictitiousQ0 (double) and connected/synchronous component number (int) are held
    // columnarly in the network-level configured-bus store (see NumericVariantStore)
    private static final String STORE_KEY = "ConfiguredBus";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN, 0.0, 0.0};
    private static final int[] INT_DEFAULTS = {-1, -1};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_V = 0;
    private static final int COL_ANGLE = 1;
    private static final int COL_FIC_P0 = 2;
    private static final int COL_FIC_Q0 = 3;
    private static final int COL_CC = 0;
    private static final int COL_SC = 1;

    private NumericVariantStore variantStore;
    private int busVariantStoreRow;

    ConfiguredBusImpl(String id, String name, boolean fictitious, VoltageLevelExt voltageLevel) {
        super(id, name, fictitious, voltageLevel);
        network = voltageLevel.getNetworkRef();
        int variantArraySize = network.get().getVariantManager().getVariantArraySize();
        terminals = new ArrayList<>(variantArraySize);
        for (int i = 0; i < variantArraySize; i++) {
            terminals.add(new ArrayList<>());
        }
        this.variantStore = network.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.busVariantStoreRow = variantStore.allocateRow();
    }

    private NumericVariantStore store() {
        return variantStore;
    }

    @Override
    public int getConnectedTerminalCount() {
        return (int) getConnectedTerminalStream().count();
    }

    @Override
    public List<TerminalExt> getConnectedTerminals() {
        return getConnectedTerminalStream().collect(Collectors.toList());
    }

    @Override
    public Stream<TerminalExt> getConnectedTerminalStream() {
        Stream<TerminalExt> own = getTerminals().stream().filter(Terminal::isConnected).map(Function.identity());
        VariantScopedMembership membership = network.get().getVariantScopedMembership();
        if (membership == null) {
            return own; // all normal use: no structural variant machinery, unchanged
        }
        return foldMembership(own, true);
    }

    // Structural variants: this bus also carries the terminals attached onto it in the active variant, and
    // hides the ones detached from it, so every read derived from the connected terminals (typed accessors,
    // equipment visitors, bus-view merging, component traversal) sees the variant's own topology.
    private Stream<TerminalExt> foldMembership(Stream<TerminalExt> own, boolean connectedOnly) {
        VariantScopedMembership membership = network.get().getVariantScopedMembership();
        VoltageLevelExt vl = (VoltageLevelExt) getVoltageLevel();
        List<TerminalExt> detached = membershipTerminalsOnThisBus(membership.detachedTerminals(vl), connectedOnly);
        Stream<TerminalExt> visible = detached.isEmpty() ? own : own.filter(t -> !detached.contains(t));
        List<TerminalExt> attached = membershipTerminalsOnThisBus(membership.attachedTerminals(vl), connectedOnly);
        return attached.isEmpty() ? visible : Stream.concat(visible, attached.stream());
    }

    private List<TerminalExt> membershipTerminalsOnThisBus(java.util.Set<TerminalExt> terminalSet, boolean connectedOnly) {
        if (terminalSet.isEmpty()) {
            return List.of();
        }
        List<TerminalExt> result = new ArrayList<>();
        for (TerminalExt terminal : terminalSet) {
            if (terminal instanceof BusTerminal busTerminal && getId().equals(busTerminal.getConnectableBusId())
                    && (!connectedOnly || busTerminal.isConnected())) {
                result.add(terminal);
            }
        }
        return result;
    }

    @Override
    public void visitConnectedOrConnectableEquipments(TopologyVisitor visitor) {
        VariantScopedMembership membership = network.get().getVariantScopedMembership();
        if (membership == null) {
            super.visitConnectedOrConnectableEquipments(visitor);
            return;
        }
        Stream<TerminalExt> own = getTerminals().stream().map(Function.identity());
        AbstractBus.visitEquipments(foldMembership(own, false).toList(), visitor);
    }

    @Override
    public int getTerminalCount() {
        return terminals.get(network.get().getVariantIndex()).size();
    }

    @Override
    public List<BusTerminal> getTerminals() {
        return terminals.get(network.get().getVariantIndex());
    }

    @Override
    public void addTerminal(BusTerminal t) {
        terminals.get(network.get().getVariantIndex()).add(t);
    }

    @Override
    public void removeTerminal(BusTerminal t) {
        if (!terminals.get(network.get().getVariantIndex()).remove(t)) {
            throw new IllegalStateException("Terminal " + t + " not found");
        }
    }

    protected <S, T extends S> void notifyUpdate(String attribute, String variantId, S oldValue, T newValue) {
        network.get().getListeners().notifyUpdate(this, attribute, variantId, oldValue, newValue);
    }

    @Override
    public double getV() {
        return store().getDouble(network.get().getVariantIndex(), COL_V, busVariantStoreRow);
    }

    @Override
    public BusExt setV(double v) {
        if (v < 0) {
            throw new ValidationException(this, "voltage cannot be < 0");
        }
        int variantIndex = network.get().getVariantIndex();
        double oldValue = store().setDouble(variantIndex, COL_V, busVariantStoreRow, v);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        notifyUpdate("v", variantId, oldValue, v);
        return this;
    }

    @Override
    public double getAngle() {
        return store().getDouble(network.get().getVariantIndex(), COL_ANGLE, busVariantStoreRow);
    }

    @Override
    public BusExt setAngle(double angle) {
        int variantIndex = network.get().getVariantIndex();
        double oldValue = store().setDouble(variantIndex, COL_ANGLE, busVariantStoreRow, angle);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        notifyUpdate("angle", variantId, oldValue, angle);
        return this;
    }

    @Override
    public double getFictitiousP0() {
        return store().getDouble(network.get().getVariantIndex(), COL_FIC_P0, busVariantStoreRow);
    }

    @Override
    public Bus setFictitiousP0(double p0) {
        if (Double.isNaN(p0)) {
            throw new ValidationException(this, "undefined value cannot be set as fictitious p0");
        }
        int variantIndex = network.get().getVariantIndex();
        double oldValue = store().setDouble(variantIndex, COL_FIC_P0, busVariantStoreRow, p0);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        notifyUpdate("fictitiousP0", variantId, oldValue, p0);
        return this;
    }

    @Override
    public double getFictitiousQ0() {
        return store().getDouble(network.get().getVariantIndex(), COL_FIC_Q0, busVariantStoreRow);
    }

    @Override
    public Bus setFictitiousQ0(double q0) {
        if (Double.isNaN(q0)) {
            throw new ValidationException(this, "undefined value cannot be set as fictitious q0");
        }
        int variantIndex = network.get().getVariantIndex();
        double oldValue = store().setDouble(variantIndex, COL_FIC_Q0, busVariantStoreRow, q0);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        notifyUpdate("fictitiousQ0", variantId, oldValue, q0);
        return this;
    }

    @Override
    public void setConnectedComponentNumber(int connectedComponentNumber) {
        int variantIndex = network.get().getVariantIndex();
        int oldValue = store().setInt(variantIndex, COL_CC, busVariantStoreRow, connectedComponentNumber);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        notifyUpdate("connectedComponentNumber", variantId, oldValue, connectedComponentNumber);
    }

    @Override
    public Component getConnectedComponent() {
        NetworkImpl.ConnectedComponentsManager ccm = voltageLevel.getNetwork().getConnectedComponentsManager();
        ccm.update();
        return ccm.getComponent(store().getInt(network.get().getVariantIndex(), COL_CC, busVariantStoreRow));
    }

    @Override
    public void setSynchronousComponentNumber(int componentNumber) {
        int variantIndex = network.get().getVariantIndex();
        int oldValue = store().setInt(variantIndex, COL_SC, busVariantStoreRow, componentNumber);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        notifyUpdate("synchronousComponentNumber", variantId, oldValue, componentNumber);
    }

    @Override
    public Component getSynchronousComponent() {
        NetworkImpl.SynchronousComponentsManager scm = voltageLevel.getNetwork().getSynchronousComponentsManager();
        scm.update();
        return scm.getComponent(store().getInt(network.get().getVariantIndex(), COL_SC, busVariantStoreRow));
    }

    // v/angle/fictitious/component numbers are maintained columnarly by the network-level configured-bus store,
    // driven once per variant operation by NetworkImpl; only the per-variant terminal list is handled here.
    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);

        terminals.ensureCapacity(terminals.size() + number);
        for (int i = 0; i < number; i++) {
            terminals.add(new ArrayList<>(terminals.get(sourceIndex)));
        }
    }

    @Override
    public void reduceVariantArraySize(int number) {
        super.reduceVariantArraySize(number);

        for (int i = 0; i < number; i++) {
            terminals.remove(terminals.size() - 1);
        }
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        super.deleteVariantArrayElement(index);

        terminals.set(index, null);
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);

        for (int index : indexes) {
            terminals.set(index, new ArrayList<>(terminals.get(sourceIndex)));
        }
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork); // extensions
        NumericVariantStore oldStore = store();
        double v0 = oldStore.getDouble(0, COL_V, busVariantStoreRow);
        double angle0 = oldStore.getDouble(0, COL_ANGLE, busVariantStoreRow);
        double ficP0 = oldStore.getDouble(0, COL_FIC_P0, busVariantStoreRow);
        double ficQ0 = oldStore.getDouble(0, COL_FIC_Q0, busVariantStoreRow);
        int cc0 = oldStore.getInt(0, COL_CC, busVariantStoreRow);
        int sc0 = oldStore.getInt(0, COL_SC, busVariantStoreRow);
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStore = newStore;
        this.busVariantStoreRow = newStore.allocateRow();
        newStore.setDouble(0, COL_V, busVariantStoreRow, v0);
        newStore.setDouble(0, COL_ANGLE, busVariantStoreRow, angle0);
        newStore.setDouble(0, COL_FIC_P0, busVariantStoreRow, ficP0);
        newStore.setDouble(0, COL_FIC_Q0, busVariantStoreRow, ficQ0);
        newStore.setInt(0, COL_CC, busVariantStoreRow, cc0);
        newStore.setInt(0, COL_SC, busVariantStoreRow, sc0);
    }

}
