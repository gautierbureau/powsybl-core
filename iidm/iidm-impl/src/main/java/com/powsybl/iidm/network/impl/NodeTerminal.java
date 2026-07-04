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
import com.powsybl.iidm.network.*;
import com.powsybl.math.graph.TraversalType;

import java.util.Set;

/**
 * A terminal connected to a node breaker topology.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class NodeTerminal extends AbstractTerminal {

    private final int node;

    // attributes depending on the variant, held columnarly in the network-level node-terminal store
    // (see NumericVariantStore): columns v, angle (double) and connected/synchronous component number (int)
    private static final String STORE_KEY = "NodeTerminal";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {0, 0};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_V = 0;
    private static final int COL_ANGLE = 1;
    private static final int COL_CC = 0;
    private static final int COL_SC = 1;

    private NumericVariantStore nodeVariantStore;
    private int nodeVariantStoreRow;

    private final NodeBreakerView nodeBreakerView = new NodeBreakerView() {

        @Override
        public int getNode() {
            if (removed) {
                throw new PowsyblException("Cannot access node of removed equipment " + connectable.id);
            }
            return node;
        }

        @Override
        public void moveConnectable(int node, String voltageLevelId) {
            if (removed) {
                throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
            }
            getConnectable().move(NodeTerminal.this, node, voltageLevelId);
        }
    };

    private NodeBreakerTopologyModel getTopologyModel() {
        return (NodeBreakerTopologyModel) voltageLevel.getTopologyModel();
    }

    private final BusBreakerViewExt busBreakerView = new BusBreakerViewExt() {

        @Override
        public BusExt getBus() {
            if (removed) {
                throw new PowsyblException(CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT + connectable.id);
            }
            return getTopologyModel().getCalculatedBusBreakerTopology().getBus(node);
        }

        @Override
        public BusExt getConnectableBus() {
            if (removed) {
                throw new PowsyblException(CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT + connectable.id);
            }
            return getTopologyModel().getCalculatedBusBreakerTopology().getConnectableBus(node);
        }

        @Override
        public void setConnectableBus(String busId) {
            throw NodeBreakerTopologyModel.createNotSupportedNodeBreakerTopologyException();
        }

        @Override
        public void moveConnectable(String busId, boolean connected) {
            if (removed) {
                throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
            }
            getConnectable().move(NodeTerminal.this, busId, connected);
        }

    };

    @Override
    public TopologyPoint getTopologyPoint() {
        return new NodeTopologyPointImpl(getVoltageLevel().getId(), getNode());
    }

    private final BusViewExt busView = new BusViewExt() {

        @Override
        public BusExt getBus() {
            if (removed) {
                throw new PowsyblException(CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT + connectable.id);
            }
            return getTopologyModel().getCalculatedBusTopology().getBus(node);
        }

        @Override
        public BusExt getConnectableBus() {
            if (removed) {
                throw new PowsyblException(CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT + connectable.id);
            }
            return getTopologyModel().getCalculatedBusTopology().getConnectableBus(node);
        }

    };

    NodeTerminal(Ref<? extends VariantManagerHolder> network, ThreeSides side, TerminalNumber terminalNumber, int node) {
        super(network, side, terminalNumber);
        this.node = node;
        this.nodeVariantStore = network.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.nodeVariantStoreRow = nodeVariantStore.allocateRow();
    }

    protected void notifyUpdate(String attribute, String variantId, Object oldValue, Object newValue) {
        getConnectable().notifyUpdate(attribute, variantId, oldValue, newValue);
    }

    public int getNode() {
        return node;
    }

    @Override
    protected double getV() {
        if (removed) {
            throw new PowsyblException("Cannot access v of removed equipment " + connectable.id);
        }
        VariantManagerHolder holder = getVariantManagerHolder();
        return nodeVariantStore.getDouble(holder.getVariantIndex(), COL_V, nodeVariantStoreRow);
    }

    void setV(double v) {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        if (v < 0) {
            throw new ValidationException(connectable, "voltage cannot be < 0");
        }
        VariantManagerHolder holder = getVariantManagerHolder();
        int variantIndex = holder.getVariantIndex();
        double oldValue = nodeVariantStore.setDouble(variantIndex, COL_V, nodeVariantStoreRow, v);
        String variantId = holder.getVariantManager().getVariantId(variantIndex);
        notifyUpdate("v", variantId, oldValue, v);
    }

    double getAngle() {
        if (removed) {
            throw new PowsyblException("Cannot access angle of removed equipment " + connectable.id);
        }
        VariantManagerHolder holder = getVariantManagerHolder();
        return nodeVariantStore.getDouble(holder.getVariantIndex(), COL_ANGLE, nodeVariantStoreRow);
    }

    void setAngle(double angle) {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        VariantManagerHolder holder = getVariantManagerHolder();
        int variantIndex = holder.getVariantIndex();
        double oldValue = nodeVariantStore.setDouble(variantIndex, COL_ANGLE, nodeVariantStoreRow, angle);
        String variantId = holder.getVariantManager().getVariantId(variantIndex);
        notifyUpdate("angle", variantId, oldValue, angle);
    }

    int getConnectedComponentNumber() {
        if (removed) {
            throw new PowsyblException("Cannot access connected component of removed equipment " + connectable.id);
        }
        VariantManagerHolder holder = getVariantManagerHolder();
        return nodeVariantStore.getInt(holder.getVariantIndex(), COL_CC, nodeVariantStoreRow);
    }

    void setConnectedComponentNumber(int connectedComponentNumber) {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        VariantManagerHolder holder = getVariantManagerHolder();
        int variantIndex = holder.getVariantIndex();
        int oldValue = nodeVariantStore.setInt(variantIndex, COL_CC, nodeVariantStoreRow, connectedComponentNumber);
        String variantId = holder.getVariantManager().getVariantId(variantIndex);
        notifyUpdate("connectedComponentNumber", variantId, oldValue, connectedComponentNumber);
    }

    int getSynchronousComponentNumber() {
        if (removed) {
            throw new PowsyblException("Cannot access synchronous component of removed equipment " + connectable.id);
        }
        VariantManagerHolder holder = getVariantManagerHolder();
        return nodeVariantStore.getInt(holder.getVariantIndex(), COL_SC, nodeVariantStoreRow);
    }

    void setSynchronousComponentNumber(int componentNumber) {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        VariantManagerHolder holder = getVariantManagerHolder();
        int variantIndex = holder.getVariantIndex();
        int oldValue = nodeVariantStore.setInt(variantIndex, COL_SC, nodeVariantStoreRow, componentNumber);
        String variantId = holder.getVariantManager().getVariantId(variantIndex);
        notifyUpdate("synchronousComponentNumber", variantId, oldValue, componentNumber);
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
    public boolean isConnected() {
        if (removed) {
            throw new PowsyblException("Cannot access connectivity status of removed equipment " + connectable.id);
        }
        return getTopologyModel().isConnected(this);
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

    // v/angle/component numbers are maintained columnarly by the network-level node-terminal store, driven
    // once per variant operation by NetworkImpl; these per-terminal hooks (super handles p/q) have nothing
    // more to do.
    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
    }

    @Override
    public void reduceVariantArraySize(int number) {
        super.reduceVariantArraySize(number);
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        super.allocateVariantArrayElement(indexes, sourceIndex);
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork);
        NumericVariantStore oldStore = nodeVariantStore;
        double v0 = oldStore.getDouble(0, COL_V, nodeVariantStoreRow);
        double angle0 = oldStore.getDouble(0, COL_ANGLE, nodeVariantStoreRow);
        int cc0 = oldStore.getInt(0, COL_CC, nodeVariantStoreRow);
        int sc0 = oldStore.getInt(0, COL_SC, nodeVariantStoreRow);
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.nodeVariantStore = newStore;
        this.nodeVariantStoreRow = newStore.allocateRow();
        newStore.setDouble(0, COL_V, nodeVariantStoreRow, v0);
        newStore.setDouble(0, COL_ANGLE, nodeVariantStoreRow, angle0);
        newStore.setInt(0, COL_CC, nodeVariantStoreRow, cc0);
        newStore.setInt(0, COL_SC, nodeVariantStoreRow, sc0);
    }

    @Override
    public void remove() {
        boolean wasRemoved = removed;
        super.remove(); // frees the p/q store row (and sets removed)
        if (!wasRemoved) {
            nodeVariantStore.freeRow(nodeVariantStoreRow);
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + node + "]";
    }
}
