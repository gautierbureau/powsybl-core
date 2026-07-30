/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.ref.Ref;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.ValidationException;
import com.powsybl.iidm.network.ValidationUtil;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
abstract class AbstractTapChanger<H extends TapChangerParent, C extends AbstractTapChanger<H, C, S>, S extends TapChangerStepImpl<S>> extends AbstractPropertiesHolder implements MultiVariantObject {

    // targetDeadband / regulationValue (double) and tapPosition / solvedTapPosition (int) are held columnarly
    // in a network-level NumericVariantStore (structure-of-arrays) shared by all tap changers, so a variant
    // clone copies them all at once with a bulk array copy instead of once per tap changer. tapPosition and
    // solvedTapPosition are nullable (unset); TAP_NULL encodes the null state in the int columns.
    protected static final String STORE_KEY = "TapChanger";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    static final int TAP_NULL = Integer.MIN_VALUE;
    private static final int[] INT_DEFAULTS = {TAP_NULL, TAP_NULL};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    protected static final int COL_TARGET_DEADBAND = 0;
    protected static final int COL_REGULATION_VALUE = 1;
    protected static final int COL_TAP_POSITION = 0;
    protected static final int COL_SOLVED_TAP_POSITION = 1;

    protected final Ref<? extends VariantManagerHolder> network;

    protected final H parent;

    protected boolean loadTapChangingCapabilities;

    protected int lowTapPosition;

    protected Integer relativeNeutralPosition;

    protected List<S> steps;

    private final String type;

    protected final RegulatingPoint regulatingPoint;

    // attributes depending on the variant, held columnarly in the store above (this tap changer owns one row)
    protected NumericVariantStore variantStore;
    protected int variantStoreRow;

    protected AbstractTapChanger(H parent,
                                 int lowTapPosition, List<S> steps, TerminalExt regulationTerminal,
                                 boolean loadTapChangingCapabilities,
                                 Integer tapPosition, Integer solvedTapPosition, boolean regulating,
                                 double targetDeadband, double regulationValue, String type) {
        // The Ref object should be the one corresponding to the subnetwork of the tap changer holder
        // (to avoid errors when the subnetwork is detached)
        this.network = parent.getParentNetwork().getRootNetworkRef();
        this.parent = parent;
        this.loadTapChangingCapabilities = loadTapChangingCapabilities;
        this.lowTapPosition = lowTapPosition;
        this.steps = steps;
        steps.forEach(s -> s.setParent(this));
        regulatingPoint = createRegulatingPoint(regulating);
        regulatingPoint.setRegulatingTerminal(regulationTerminal);
        this.variantStore = network.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {targetDeadband, regulationValue},
                new int[] {tapPosition == null ? TAP_NULL : tapPosition, solvedTapPosition == null ? TAP_NULL : solvedTapPosition},
                BOOLEAN_DEFAULTS);
        this.type = Objects.requireNonNull(type);
        relativeNeutralPosition = getRelativeNeutralPosition();
    }

    protected abstract RegulatingPoint createRegulatingPoint(boolean regulating);

    protected NetworkImpl getNetwork() {
        return parent.getNetwork();
    }

    protected abstract Integer getRelativeNeutralPosition();

    public int getStepCount() {
        return steps.size();
    }

    public int getLowTapPosition() {
        return lowTapPosition;
    }

    public C setLowTapPosition(int lowTapPosition) {
        int oldValue = this.lowTapPosition;
        this.lowTapPosition = lowTapPosition;
        parent.getNetwork().getListeners().notifyUpdate(parent.getTransformer(), () -> getTapChangerAttribute() + ".lowTapPosition", oldValue, lowTapPosition);
        int variantIndex = network.get().getVariantIndex();
        int raw = variantStore.getInt(variantIndex, COL_TAP_POSITION, variantStoreRow);
        if (raw != TAP_NULL) {
            variantStore.setInt(variantIndex, COL_TAP_POSITION, variantStoreRow, raw + (this.lowTapPosition - oldValue));
        }
        return (C) this;
    }

    public int getHighTapPosition() {
        return lowTapPosition + steps.size() - 1;
    }

    public int getTapPosition() {
        int raw = variantStore.getInt(network.get().getVariantIndex(), COL_TAP_POSITION, variantStoreRow);
        if (raw == TAP_NULL) {
            throw ValidationUtil.createUndefinedValueGetterException();
        }
        return raw;
    }

    public OptionalInt findTapPosition() {
        int raw = variantStore.getInt(network.get().getVariantIndex(), COL_TAP_POSITION, variantStoreRow);
        return raw == TAP_NULL ? OptionalInt.empty() : OptionalInt.of(raw);
    }

    public Integer getSolvedTapPosition() {
        int raw = variantStore.getInt(network.get().getVariantIndex(), COL_SOLVED_TAP_POSITION, variantStoreRow);
        return raw == TAP_NULL ? null : raw;
    }

    public OptionalInt findSolvedTapPosition() {
        int raw = variantStore.getInt(network.get().getVariantIndex(), COL_SOLVED_TAP_POSITION, variantStoreRow);
        return raw == TAP_NULL ? OptionalInt.empty() : OptionalInt.of(raw);
    }

    public OptionalInt getNeutralPosition() {
        return relativeNeutralPosition != null ? OptionalInt.of(lowTapPosition + relativeNeutralPosition) : OptionalInt.empty();
    }

    protected abstract String getTapChangerAttribute();

    public C setTapPosition(int tapPosition) {
        NetworkImpl n = getNetwork();
        if (tapPosition < lowTapPosition
            || tapPosition > getHighTapPosition()) {
            throwIncorrectTapPosition(tapPosition, getHighTapPosition());
        }
        int variantIndex = n.getVariantIndex();
        int oldRaw = variantStore.setInt(variantIndex, COL_TAP_POSITION, variantStoreRow, tapPosition);
        Integer oldValue = oldRaw == TAP_NULL ? null : oldRaw;
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        parent.getNetwork().getListeners().notifyUpdate(parent.getTransformer(), () -> getTapChangerAttribute() + ".tapPosition", variantId, oldValue, tapPosition);
        return (C) this;
    }

    public C unsetTapPosition() {
        NetworkImpl n = getNetwork();
        ValidationUtil.throwExceptionOrIgnore(parent, "tap position has been unset", n.getMinValidationLevel());
        int variantIndex = network.get().getVariantIndex();
        int oldRaw = variantStore.setInt(variantIndex, COL_TAP_POSITION, variantStoreRow, TAP_NULL);
        Integer oldValue = oldRaw == TAP_NULL ? null : oldRaw;
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        n.getListeners().notifyUpdate(parent.getTransformer(), () -> getTapChangerAttribute() + ".tapPosition", variantId, oldValue, null);
        return (C) this;
    }

    public C setSolvedTapPosition(int solvedTapPosition) {
        NetworkImpl n = getNetwork();
        if (solvedTapPosition < lowTapPosition
            || solvedTapPosition > getHighTapPosition()) {
            throwIncorrectSolvedTapPosition(solvedTapPosition, getHighTapPosition());
        }
        int variantIndex = n.getVariantIndex();
        int oldRaw = variantStore.setInt(variantIndex, COL_SOLVED_TAP_POSITION, variantStoreRow, solvedTapPosition);
        Integer oldValue = oldRaw == TAP_NULL ? null : oldRaw;
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        parent.getNetwork().getListeners().notifyUpdate(parent.getTransformer(), () -> getTapChangerAttribute() + ".solvedTapPosition", variantId, oldValue, solvedTapPosition);
        return (C) this;
    }

    public C unsetSolvedTapPosition() {
        NetworkImpl n = getNetwork();
        int variantIndex = network.get().getVariantIndex();
        int oldRaw = variantStore.setInt(variantIndex, COL_SOLVED_TAP_POSITION, variantStoreRow, TAP_NULL);
        Integer oldValue = oldRaw == TAP_NULL ? null : oldRaw;
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.getListeners().notifyUpdate(parent.getTransformer(), () -> getTapChangerAttribute() + ".solvedTapPosition", variantId, oldValue, null);
        return (C) this;
    }

    public S getStep(int tapPosition) {
        if (tapPosition < lowTapPosition || tapPosition > getHighTapPosition()) {
            throwIncorrectTapPosition(tapPosition, getHighTapPosition());
        }
        return steps.get(tapPosition - lowTapPosition);
    }

    protected C setSteps(List<S> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new ValidationException(parent, "a tap changer shall have at least one step");
        }
        steps.forEach(step -> step.validate(parent));

        // We check if the tap position is still correct
        int newHighTapPosition = lowTapPosition + steps.size() - 1;
        if (getTapPosition() > newHighTapPosition) {
            throwIncorrectTapPosition(getTapPosition(), newHighTapPosition);
        }
        // We check if the solved tap position is still correct
        OptionalInt solvedTap = findSolvedTapPosition();
        if (solvedTap.isPresent() && solvedTap.getAsInt() > newHighTapPosition) {
            throwIncorrectSolvedTapPosition(solvedTap.getAsInt(), newHighTapPosition);
        }

        this.steps = steps;
        this.relativeNeutralPosition = getRelativeNeutralPosition();
        return (C) this;
    }

    public S getCurrentStep() {
        int raw = variantStore.getInt(network.get().getVariantIndex(), COL_TAP_POSITION, variantStoreRow);
        if (raw == TAP_NULL) {
            return null;
        }
        return getStep(raw);
    }

    public S getSolvedCurrentStep() {
        int raw = variantStore.getInt(network.get().getVariantIndex(), COL_SOLVED_TAP_POSITION, variantStoreRow);
        if (raw == TAP_NULL) {
            return null;
        }
        return getStep(raw);
    }

    public boolean isRegulating() {
        return regulatingPoint.isRegulating(network.get().getVariantIndex());
    }

    public C setRegulating(boolean regulating) {
        NetworkImpl n = getNetwork();
        int variantIndex = network.get().getVariantIndex();
        ValidationUtil.checkTargetDeadband(parent, type, regulating,
                variantStore.getDouble(variantIndex, COL_TARGET_DEADBAND, variantStoreRow),
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        boolean oldValue = regulatingPoint.setRegulating(variantIndex, regulating);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        n.getListeners().notifyUpdate(parent.getTransformer(), () -> getTapChangerAttribute() + ".regulating", variantId, oldValue, regulating);
        return (C) this;
    }

    public TerminalExt getRegulationTerminal() {
        return regulatingPoint.getRegulatingTerminal();
    }

    public C setRegulationTerminal(Terminal regulationTerminal) {
        if (regulationTerminal != null && ((TerminalExt) regulationTerminal).getVoltageLevel().getNetwork() != getNetwork()) {
            throw new ValidationException(parent, "regulation terminal is not part of the network");
        }
        Terminal oldValue = regulatingPoint.getRegulatingTerminal();
        regulatingPoint.setRegulatingTerminal((TerminalExt) regulationTerminal);
        getNetwork().getListeners().notifyUpdate(parent.getTransformer(), () -> getTapChangerAttribute() + ".regulationTerminal", oldValue, regulationTerminal);
        return (C) this;
    }

    public double getTargetDeadband() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_TARGET_DEADBAND, variantStoreRow);
    }

    public C setTargetDeadband(double targetDeadband) {
        int variantIndex = network.get().getVariantIndex();
        NetworkImpl n = getNetwork();
        ValidationUtil.checkTargetDeadband(parent, type, regulatingPoint.isRegulating(variantIndex),
                targetDeadband, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        double oldValue = variantStore.setDouble(variantIndex, COL_TARGET_DEADBAND, variantStoreRow, targetDeadband);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        n.getListeners().notifyUpdate(parent.getTransformer(), () -> getTapChangerAttribute() + ".targetDeadband", variantId, oldValue, targetDeadband);
        return (C) this;
    }

    // targetDeadband / regulationValue / tapPosition / solvedTapPosition are maintained columnarly by the
    // network-level NumericVariantStore, driven once per variant op by NetworkImpl; regulating is likewise
    // held columnarly by the RegulatingPoint's store. These per-object hooks therefore have nothing to do.
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
    public void allocateVariantArrayElement(int[] indexes, final int sourceIndex) {
        // handled by NumericVariantStore
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
        regulatingPoint.reHomeVariantStores(targetNetwork);
    }

    private void throwIncorrectTapPosition(int tapPosition, int highTapPosition) {
        throw new ValidationException(parent, "incorrect tap position "
            + tapPosition + " [" + lowTapPosition + ", " + highTapPosition
            + "]");
    }

    private void throwIncorrectSolvedTapPosition(int solvedTapPosition, int highTapPosition) {
        throw new ValidationException(parent, "incorrect solved tap position "
            + solvedTapPosition + " [" + lowTapPosition + ", " + highTapPosition
            + "]");
    }

    public void remove() {
        regulatingPoint.remove();
    }
}
