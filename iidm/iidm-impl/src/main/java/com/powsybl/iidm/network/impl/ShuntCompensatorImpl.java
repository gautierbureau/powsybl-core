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

import java.util.Objects;
import java.util.OptionalInt;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class ShuntCompensatorImpl extends AbstractConnectable<ShuntCompensator> implements ShuntCompensator {

    private static final String SHUNT_COMPENSATOR = "shunt compensator";

    private final Ref<? extends VariantManagerHolder> network;

    private final ShuntCompensatorModelExt model;

    /* the regulating terminal */
    private final RegulatingPoint regulatingPoint;

    // Variant-dependent targetV / targetDeadband and the section counts, held columnarly (see
    // NumericVariantStore). The counts used to be per-object ArrayList<Integer> grown eagerly on every clone,
    // which is growth that cannot happen on a worker thread; the store's bands are published copy-on-write
    // through a volatile and grow safely, so they moved here.
    private static final String STORE_KEY = "ShuntCompensator";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    // Both counts are nullable (null = not set, see unsetSectionCount): a validated count is always in
    // [0, maximumSectionCount], so a negative sentinel cannot collide with a real value.
    private static final int NOT_SET = Integer.MIN_VALUE;
    private static final int[] INT_DEFAULTS = {NOT_SET, NOT_SET};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_TARGET_V = 0;
    private static final int COL_TARGET_DEADBAND = 1;
    private static final int COL_SECTION_COUNT = 0;
    private static final int COL_SOLVED_SECTION_COUNT = 1;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    ShuntCompensatorImpl(Ref<NetworkImpl> network,
                         String id, String name, boolean fictitious, ShuntCompensatorModelExt model,
                         Integer sectionCount, Integer solvedSectionCount, TerminalExt regulatingTerminal,
                         Boolean voltageRegulatorOn, double targetV, double targetDeadband) {
        super(network, id, name, fictitious);
        this.network = network;
        regulatingPoint = new RegulatingPoint(id, this::getTerminal, network, voltageRegulatorOn, true);
        regulatingPoint.setRegulatingTerminal(regulatingTerminal);
        this.variantStore = network.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {targetV, targetDeadband},
                new int[] {box(sectionCount), box(checkSolvedSectionCount(solvedSectionCount, model.getMaximumSectionCount()))},
                BOOLEAN_DEFAULTS);
        this.model = Objects.requireNonNull(model).attach(this);
    }

    @Override
    public TerminalExt getTerminal() {
        return terminals.get(0);
    }

    /** {@code null} becomes the not-set sentinel on the way into the int column. */
    private static int box(Integer value) {
        return value == null ? NOT_SET : value;
    }

    /** The stored int for {@code col} in the working variant, or {@code null} when it is not set. */
    private Integer readCount(int col) {
        int value = variantStore.getInt(network.get().getVariantIndex(), col, variantStoreRow);
        return value == NOT_SET ? null : value;
    }

    @Override
    public int getSectionCount() {
        Integer section = readCount(COL_SECTION_COUNT);
        if (section == null) {
            throw ValidationUtil.createUndefinedValueGetterException();
        }
        return section;
    }

    @Override
    public Integer getSolvedSectionCount() {
        return readCount(COL_SOLVED_SECTION_COUNT);
    }

    @Override
    public OptionalInt findSectionCount() {
        Integer section = readCount(COL_SECTION_COUNT);
        return section == null ? OptionalInt.empty() : OptionalInt.of(section);
    }

    @Override
    public int getMaximumSectionCount() {
        return model.getMaximumSectionCount();
    }

    @Override
    public ShuntCompensatorImpl setSectionCount(int sectionCount) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkSections(this, sectionCount, model.getMaximumSectionCount(), getNetwork().getMinValidationLevel(),
                getNetwork().getReportNodeContext().getReportNode());
        if (sectionCount < 0 || sectionCount > model.getMaximumSectionCount()) {
            throw new ValidationException(this, "unexpected section number (" + sectionCount + "): no existing associated section");
        }
        int variantIndex = n.getVariantIndex();
        Integer oldValue = writeCount(variantIndex, COL_SECTION_COUNT, sectionCount);
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("sectionCount", variantId, oldValue, sectionCount);
        return this;
    }

    @Override
    public ShuntCompensator unsetSectionCount() {
        NetworkImpl n = getNetwork();
        ValidationUtil.throwExceptionOrIgnore(this, "count of sections in service has been unset", n.getMinValidationLevel());
        int variantIndex = network.get().getVariantIndex();
        Integer oldValue = writeCount(variantIndex, COL_SECTION_COUNT, null);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("sectionCount", variantId, oldValue, null);
        return this;
    }

    @Override
    public ShuntCompensatorImpl setSolvedSectionCount(int solvedSectionCount) {
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        Integer oldValue = writeCount(variantIndex, COL_SOLVED_SECTION_COUNT,
                checkSolvedSectionCount(solvedSectionCount, this.model.getMaximumSectionCount()));
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        notifyUpdate("solvedSectionCount", variantId, oldValue, solvedSectionCount);
        return this;
    }

    @Override
    public ShuntCompensator unsetSolvedSectionCount() {
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        Integer oldValue = writeCount(variantIndex, COL_SOLVED_SECTION_COUNT, null);
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        notifyUpdate("solvedSectionCount", variantId, oldValue, null);
        return this;
    }

    /** Write {@code value} ({@code null} = unset) into the int column and return the previous value. */
    private Integer writeCount(int variantIndex, int col, Integer value) {
        int old = variantStore.setInt(variantIndex, col, variantStoreRow, box(value));
        return old == NOT_SET ? null : old;
    }

    @Override
    public double getB() {
        return model.getB(readCount(COL_SECTION_COUNT));
    }

    @Override
    public double getG() {
        return model.getG(readCount(COL_SECTION_COUNT));
    }

    @Override
    public double getB(int sectionCount) {
        return model.getB(sectionCount);
    }

    @Override
    public double getG(int sectionCount) {
        return model.getG(sectionCount);
    }

    @Override
    public ShuntCompensatorModelType getModelType() {
        return model.getType();
    }

    @Override
    public ShuntCompensatorModel getModel() {
        return model;
    }

    @Override
    public <M extends ShuntCompensatorModel> M getModel(Class<M> modelType) {
        if (modelType == null) {
            throw new IllegalArgumentException("shunt compensator model type is null");
        }
        if (modelType.isInstance(model)) {
            return modelType.cast(model);
        }
        throw new ValidationException(this, "incorrect shunt compensator model type " +
                modelType.getName() + ", expected " + model.getClass());
    }

    @Override
    public TerminalExt getRegulatingTerminal() {
        return regulatingPoint.getRegulatingTerminal();
    }

    @Override
    public ShuntCompensatorImpl setRegulatingTerminal(Terminal regulatingTerminal) {
        ValidationUtil.checkRegulatingTerminal(this, regulatingTerminal, getNetwork());
        Terminal oldValue = regulatingPoint.getRegulatingTerminal();
        regulatingPoint.setRegulatingTerminal((TerminalExt) regulatingTerminal);
        notifyUpdate("regulatingTerminal", oldValue, regulatingPoint.getRegulatingTerminal());
        return this;
    }

    @Override
    public boolean isVoltageRegulatorOn() {
        return regulatingPoint.isRegulating(network.get().getVariantIndex());
    }

    @Override
    public ShuntCompensatorImpl setVoltageRegulatorOn(boolean voltageRegulatorOn) {
        NetworkImpl n = getNetwork();
        int variantIndex = network.get().getVariantIndex();
        ValidationUtil.checkVoltageControl(this, voltageRegulatorOn, variantStore.getDouble(variantIndex, COL_TARGET_V, variantStoreRow),
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        ValidationUtil.checkTargetDeadband(this, SHUNT_COMPENSATOR, voltageRegulatorOn, variantStore.getDouble(variantIndex, COL_TARGET_DEADBAND, variantStoreRow),
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        boolean oldValue = regulatingPoint.setRegulating(variantIndex, voltageRegulatorOn);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("voltageRegulatorOn", variantId, oldValue, voltageRegulatorOn);
        return this;
    }

    @Override
    public double getTargetV() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_TARGET_V, variantStoreRow);
    }

    @Override
    public ShuntCompensatorImpl setTargetV(double targetV) {
        NetworkImpl n = getNetwork();
        int variantIndex = network.get().getVariantIndex();
        ValidationUtil.checkVoltageControl(this, regulatingPoint.isRegulating(variantIndex), targetV,
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        double oldValue = variantStore.setDouble(variantIndex, COL_TARGET_V, variantStoreRow, targetV);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("targetV", variantId, oldValue, targetV);
        return this;
    }

    @Override
    public double getTargetDeadband() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_TARGET_DEADBAND, variantStoreRow);
    }

    @Override
    public ShuntCompensatorImpl setTargetDeadband(double targetDeadband) {
        NetworkImpl n = getNetwork();
        int variantIndex = network.get().getVariantIndex();
        ValidationUtil.checkTargetDeadband(this, SHUNT_COMPENSATOR, regulatingPoint.isRegulating(variantIndex), targetDeadband,
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        double oldValue = variantStore.setDouble(variantIndex, COL_TARGET_DEADBAND, variantStoreRow, targetDeadband);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("targetDeadband", variantId, oldValue, targetDeadband);
        return this;
    }

    @Override
    public void remove() {
        regulatingPoint.remove();
        super.remove();
    }

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        // the section counts are maintained columnarly by the network-level store
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
        regulatingPoint.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
    }

    @Override
    public void reduceVariantArraySize(int number) {
        // the section counts are maintained columnarly by the network-level store
        super.reduceVariantArraySize(number);
        regulatingPoint.reduceVariantArraySize(number);
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        super.deleteVariantArrayElement(index);
        regulatingPoint.deleteVariantArrayElement(index);
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, final int sourceIndex) {
        // the section counts are maintained columnarly by the network-level store
        super.allocateVariantArrayElement(indexes, sourceIndex);
        regulatingPoint.allocateVariantArrayElement(indexes, sourceIndex);
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork);
        double targetV0 = variantStore.getDouble(0, COL_TARGET_V, variantStoreRow);
        double targetDeadband0 = variantStore.getDouble(0, COL_TARGET_DEADBAND, variantStoreRow);
        int sectionCount0 = variantStore.getInt(0, COL_SECTION_COUNT, variantStoreRow);
        int solvedSectionCount0 = variantStore.getInt(0, COL_SOLVED_SECTION_COUNT, variantStoreRow);
        this.variantStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {targetV0, targetDeadband0},
                new int[] {sectionCount0, solvedSectionCount0}, BOOLEAN_DEFAULTS);
        regulatingPoint.reHomeVariantStores(targetNetwork);
    }

    @Override
    protected String getTypeDescription() {
        return "Shunt compensator";
    }

    private Integer checkSolvedSectionCount(Integer solvedSectionCount, int maximumSectionCount) {
        if (solvedSectionCount != null && (solvedSectionCount < 0 || solvedSectionCount > maximumSectionCount)) {
            throw new ValidationException(this, "unexpected solved section number (" + solvedSectionCount + "): no existing associated section");
        }
        return solvedSectionCount;
    }
}
