/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.TerminalNumber;
import com.powsybl.iidm.network.ThreeSides;
import com.powsybl.iidm.network.TopologyPoint;
import com.powsybl.math.graph.TraversalType;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * A terminal connected to a bus/breaker topology.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class BusTerminal extends AbstractTerminal {

    private BusBreakerTopologyModel getTopologyModel() {
        return (BusBreakerTopologyModel) voltageLevel.getTopologyModel();
    }

    private final NodeBreakerView nodeBreakerView = new NodeBreakerView() {
        @Override
        public int getNode() {
            throw BusBreakerTopologyModel.createNotSupportedBusBreakerTopologyException();
        }

        @Override
        public void moveConnectable(int node, String voltageLevelId) {
            if (removed) {
                throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
            }
            getConnectable().move(BusTerminal.this, node, voltageLevelId);
        }
    };

    private final BusBreakerViewExt busBreakerView = new BusBreakerViewExt() {

        @Override
        public BusExt getBus() {
            if (removed) {
                throw new PowsyblException(CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT + connectable.id);
            }
            return isConnected() ? getConnectableBus() : null;
        }

        @Override
        public ConfiguredBus getConnectableBus() {
            if (removed) {
                throw new PowsyblException(CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT + connectable.id);
            }
            return getTopologyModel().getBus(getConnectableBusId(), true);
        }

        @Override
        public void setConnectableBus(String busId) {
            if (removed) {
                throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
            }
            Objects.requireNonNull(busId);
            BusBreakerTopologyModel topologyModel = getTopologyModel();

            // Assert that the new bus exists
            topologyModel.getBus(busId, true);

            topologyModel.detachInCurrentVariant(BusTerminal.this);
            int variantIndex = getVariantManagerHolder().getVariantIndex();
            String oldValue = BusTerminal.this.connectableBusId.set(variantIndex, busId);
            topologyModel.attachInCurrentVariant(BusTerminal.this, false);
            String variantId = getVariantManagerHolder().getVariantManager().getVariantId(variantIndex);
            getConnectable().notifyUpdate("connectableBusId", variantId, oldValue, busId);
        }

        @Override
        public void moveConnectable(String busId, boolean connected) {
            if (removed) {
                throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
            }
            getConnectable().move(BusTerminal.this, busId, connected);
        }

    };

    @Override
    public TopologyPoint getTopologyPoint() {
        return new BusTopologyPointImpl(getVoltageLevel().getId(), getConnectableBusId(), isConnected());
    }

    private final BusViewExt busView = new BusViewExt() {

        @Override
        public BusExt getBus() {
            if (removed) {
                throw new PowsyblException(CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT + connectable.id);
            }
            return isConnected() ? this.getConnectableBus() : null;
        }

        @Override
        public MergedBus getConnectableBus() {
            if (removed) {
                throw new PowsyblException(CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT + connectable.id);
            }
            ConfiguredBus bus = getTopologyModel().getBus(getConnectableBusId(), true);
            return getTopologyModel().calculatedBusTopology.getMergedBus(bus);
        }

    };

    // attributes depending on the variant

    // connected is held columnarly (boolean column); connectableBusId is an object (String) list, kept per-object
    private static final String STORE_KEY = "BusTerminal";
    private static final double[] DOUBLE_DEFAULTS = {};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {false};
    private static final int COL_CONNECTED = 0;

    private NumericVariantStore connectedStore;
    private int connectedStoreRow;

    private final ArrayList<String> connectableBusId;

    BusTerminal(Ref<? extends VariantManagerHolder> network, ThreeSides side, TerminalNumber terminalNumber, String connectableBusId, boolean connected) {
        super(network, side, terminalNumber);
        Objects.requireNonNull(connectableBusId);
        int variantArraySize = network.get().getVariantManager().getVariantArraySize();
        this.connectedStore = network.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.connectedStoreRow = connectedStore.allocateRow(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {connected});
        this.connectableBusId = new ArrayList<>(variantArraySize);
        for (int i = 0; i < variantArraySize; i++) {
            this.connectableBusId.add(connectableBusId);
        }
    }

    void unsetConnectableBusId() {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        int variantIndex = getVariantManagerHolder().getVariantIndex();
        this.connectableBusId.set(variantIndex, null);
    }

    String getConnectableBusId() {
        if (removed) {
            throw new PowsyblException(CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT + connectable.id);
        }
        return this.connectableBusId.get(getVariantManagerHolder().getVariantIndex());
    }

    void setConnected(boolean connected) {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        int variantIndex = getVariantManagerHolder().getVariantIndex();
        boolean oldValue = connectedStore.setBoolean(variantIndex, COL_CONNECTED, connectedStoreRow, connected);
        String variantId = getVariantManagerHolder().getVariantManager().getVariantId(variantIndex);
        getConnectable().notifyUpdate("connected" + getAttributeSideOrNumberSuffix(), variantId, oldValue, connected);
    }

    @Override
    public boolean isConnected() {
        if (removed) {
            throw new PowsyblException("Cannot access connectivity status of removed equipment " + connectable.id);
        }
        return connectedStore.getBoolean(getVariantManagerHolder().getVariantIndex(), COL_CONNECTED, connectedStoreRow);
    }

    @Override
    public boolean traverse(TopologyTraverser traverser, Set<Terminal> visitedTerminals, TraversalType traversalType) {
        if (removed) {
            throw new PowsyblException(String.format("Associated equipment %s is removed", connectable.id));
        }
        return getTopologyModel().traverse(this, traverser, visitedTerminals, traversalType);
    }

    @Override
    public void traverse(TopologyTraverser traverser) {
        traverse(traverser, TraversalType.DEPTH_FIRST);
    }

    @Override
    public void traverse(TopologyTraverser traverser, TraversalType traversalType) {
        if (removed) {
            throw new PowsyblException(String.format("Associated equipment %s is removed", connectable.id));
        }
        getTopologyModel().traverse(this, traverser, traversalType);
    }

    @Override
    public void remove() {
        boolean wasRemoved = removed;
        super.remove(); // frees the p/q store row (and sets removed)
        if (!wasRemoved) {
            connectedStore.freeRow(connectedStoreRow);
        }
    }

    @Override
    protected double getV() {
        if (removed) {
            throw new PowsyblException("Cannot access v of removed equipment " + connectable.id);
        }
        return busBreakerView.getBus() != null ? busBreakerView.getBus().getV() : Double.NaN;
    }

    @Override
    public NodeBreakerView getNodeBreakerView() {
        return nodeBreakerView;
    }

    @Override
    public BusBreakerViewExt getBusBreakerView() {
        return busBreakerView;
    }

    @Override
    public BusViewExt getBusView() {
        return busView;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + getConnectableBusId() + "]";
    }

    // connected is maintained columnarly by the network-level store; only the connectableBusId list is here.
    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
        connectableBusId.ensureCapacity(connectableBusId.size() + number);
        for (int i = 0; i < number; i++) {
            connectableBusId.add(connectableBusId.get(sourceIndex));
        }
    }

    @Override
    public void reduceVariantArraySize(int number) {
        super.reduceVariantArraySize(number);
        for (int i = 0; i < number; i++) {
            connectableBusId.remove(connectableBusId.size() - 1);
        }
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        super.deleteVariantArrayElement(index);
        connectableBusId.set(index, null);
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);
        for (int index : indexes) {
            connectableBusId.set(index, connectableBusId.get(sourceIndex));
        }
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork); // p/q
        boolean connected0 = connectedStore.getBoolean(0, COL_CONNECTED, connectedStoreRow);
        this.connectedStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.connectedStoreRow = connectedStore.allocateRow(DOUBLE_DEFAULTS, INT_DEFAULTS, new boolean[] {connected0});
    }
}
