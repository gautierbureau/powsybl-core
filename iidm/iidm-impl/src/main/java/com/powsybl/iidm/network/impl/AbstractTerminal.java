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
import com.powsybl.iidm.network.util.SwitchPredicates;

import java.util.List;
import java.util.function.Predicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
abstract class AbstractTerminal implements TerminalExt {

    protected static final String UNMODIFIABLE_REMOVED_EQUIPMENT = "Cannot modify removed equipment ";
    protected static final String CANNOT_ACCESS_BUS_REMOVED_EQUIPMENT = "Cannot access bus of removed equipment ";

    private Ref<? extends VariantManagerHolder> network;

    protected final ThreeSides side;

    protected final TerminalNumber terminalNumber;

    protected AbstractConnectable connectable;

    protected VoltageLevelExt voltageLevel;

    protected final ReferrerManager<Terminal> referrerManager = new ReferrerManager<>(this);

    // attributes depending on the variant

    // p/q are held columnarly in the network-level TerminalVariantStore (structure-of-arrays), so a
    // variant clone extends all terminals at once with a bulk array copy instead of once per terminal.
    // This terminal owns a single row in that store (re-homed if the terminal moves network on merge/detach).
    protected int variantStoreRow;

    protected boolean removed = false;

    AbstractTerminal(Ref<? extends VariantManagerHolder> network, ThreeSides side, TerminalNumber terminalNumber) {
        if (side != null && terminalNumber != null) {
            throw new IllegalStateException("cannot have both side and number");
        }
        this.side = side;
        this.terminalNumber = terminalNumber;
        this.network = network;
        this.variantStoreRow = network.get().getTerminalVariantStore().allocateRow();
    }

    @Override
    public ThreeSides getSide() {
        return side;
    }

    @Override
    public TerminalNumber getTerminalNumber() {
        return terminalNumber;
    }

    protected String getAttributeSideOrNumberSuffix() {
        return "" + (side != null ? side.getNum() : "") + (terminalNumber != null ? terminalNumber.getNum() : "");
    }

    protected VariantManagerHolder getVariantManagerHolder() {
        return network.get();
    }

    @Override
    public AbstractConnectable getConnectable() {
        return connectable;
    }

    @Override
    public void setConnectable(AbstractConnectable connectable) {
        this.connectable = connectable;
    }

    @Override
    public VoltageLevelExt getVoltageLevel() {
        if (removed) {
            throw new PowsyblException("Cannot access voltage level of removed equipment " + connectable.id);
        }
        return voltageLevel;
    }

    @Override
    public void setVoltageLevel(VoltageLevelExt voltageLevel) {
        this.voltageLevel = voltageLevel;
        if (voltageLevel != null) {
            network = voltageLevel.getNetworkRef();
        }
    }

    @Override
    public double getP() {
        if (removed) {
            throw new PowsyblException("Cannot access p of removed equipment " + connectable.id);
        }
        VariantManagerHolder holder = network.get();
        return holder.getTerminalVariantStore().getP(holder.getVariantIndex(), variantStoreRow);
    }

    @Override
    public Terminal setP(double p) {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        if (connectable.getType() == IdentifiableType.BUSBAR_SECTION) {
            throw new ValidationException(connectable, "cannot set active power on a busbar section");
        }
        int variantIndex = network.get().getVariantIndex();
        double oldValue = network.get().getTerminalVariantStore().setP(variantIndex, variantStoreRow, p);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        getConnectable().notifyUpdate(() -> "p" + getAttributeSideOrNumberSuffix(), variantId, oldValue, p);
        return this;
    }

    @Override
    public double getQ() {
        if (removed) {
            throw new PowsyblException("Cannot access q of removed equipment " + connectable.id);
        }
        VariantManagerHolder holder = network.get();
        return holder.getTerminalVariantStore().getQ(holder.getVariantIndex(), variantStoreRow);
    }

    @Override
    public Terminal setQ(double q) {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        if (connectable.getType() == IdentifiableType.BUSBAR_SECTION) {
            throw new ValidationException(connectable, "cannot set reactive power on a busbar section");
        }
        int variantIndex = network.get().getVariantIndex();
        double oldValue = network.get().getTerminalVariantStore().setQ(variantIndex, variantStoreRow, q);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        getConnectable().notifyUpdate(() -> "q" + getAttributeSideOrNumberSuffix(), variantId, oldValue, q);
        return this;
    }

    protected abstract double getV();

    @Override
    public double getI() {
        if (removed) {
            throw new PowsyblException("Cannot access i of removed equipment " + connectable.id);
        }
        if (connectable.getType() == IdentifiableType.BUSBAR_SECTION) {
            return 0;
        }
        VariantManagerHolder holder = network.get();
        int variantIndex = holder.getVariantIndex();
        TerminalVariantStore store = holder.getTerminalVariantStore();
        return Math.hypot(store.getP(variantIndex, variantStoreRow), store.getQ(variantIndex, variantStoreRow))
                / (Math.sqrt(3.) * getV() / 1000);
    }

    /**
     * Try to connect the terminal.<br/>
     * Depends on the working variant.
     * @param isTypeSwitchToOperate Predicate telling if a switch is considered operable. Examples of predicates are available in the class {@link SwitchPredicates}
     * @return true if terminal has been connected, false otherwise
     * @see VariantManager
     */
    @Override
    public boolean connect(Predicate<Switch> isTypeSwitchToOperate) {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        int variantIndex = getVariantManagerHolder().getVariantIndex();
        String variantId = getVariantManagerHolder().getVariantManager().getVariantId(variantIndex);
        boolean connectedBefore = isConnected();
        connectable.notifyUpdate("beginConnect", variantId, connectedBefore, null);
        boolean connected = voltageLevel.getTopologyModel().connect(this, isTypeSwitchToOperate);
        boolean connectedAfter = isConnected();
        connectable.notifyUpdate("endConnect", variantId, null, connectedAfter);
        return connected;
    }

    /**
     * Disconnect the terminal.<br/>
     * Depends on the working variant.
     * @param isSwitchOpenable Predicate telling if a switch is considered openable. Examples of predicates are available in the class {@link SwitchPredicates}
     * @return true if terminal has been disconnected, false otherwise
     * @see VariantManager
     */
    @Override
    public boolean disconnect(Predicate<Switch> isSwitchOpenable) {
        if (removed) {
            throw new PowsyblException(UNMODIFIABLE_REMOVED_EQUIPMENT + connectable.id);
        }
        int variantIndex = getVariantManagerHolder().getVariantIndex();
        String variantId = getVariantManagerHolder().getVariantManager().getVariantId(variantIndex);
        boolean disconnectedBefore = !isConnected();
        connectable.notifyUpdate("beginDisconnect", variantId, disconnectedBefore, null);
        boolean disconnected = voltageLevel.getTopologyModel().disconnect(this, isSwitchOpenable);
        boolean disconnectedAfter = !isConnected();
        connectable.notifyUpdate("endDisconnect", variantId, null, disconnectedAfter);
        return disconnected;
    }

    // The variant-dependent p/q are maintained columnarly by the network-level TerminalVariantStore, which
    // the root network extends/reduces/allocates once per variant operation (see NetworkImpl). These
    // per-terminal hooks therefore have nothing to do for p/q; subclasses still handle their own arrays.
    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        // p/q handled by TerminalVariantStore
    }

    @Override
    public void reduceVariantArraySize(int number) {
        // p/q handled by TerminalVariantStore
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        // nothing to do
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        // p/q handled by TerminalVariantStore
    }

    /**
     * Move this terminal's columnar variant state into {@code targetNetwork}'s stores, allocating fresh rows.
     * Called when the terminal changes network (merge/detach), before the network reference is redirected, so
     * {@code network.get()} still resolves to the current owner. Only single-variant networks can be
     * merged/detached, so only the initial variant is transferred. Subclasses extend this for their own stores.
     */
    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        TerminalVariantStore oldStore = network.get().getTerminalVariantStore();
        TerminalVariantStore newStore = targetNetwork.getTerminalVariantStore();
        double p0 = oldStore.getP(0, variantStoreRow);
        double q0 = oldStore.getQ(0, variantStoreRow);
        this.variantStoreRow = newStore.importRow(p0, q0);
    }

    @Override
    public void remove() {
        if (!removed) {
            removed = true;
            // release the columnar store row so it can be reused by a future terminal
            network.get().getTerminalVariantStore().freeRow(variantStoreRow);
        }
    }

    @Override
    public ReferrerManager<Terminal> getReferrerManager() {
        return referrerManager;
    }

    @Override
    public List<Object> getReferrers() {
        return referrerManager.getReferrers().stream().map(r -> (Object) r).toList();
    }
}
