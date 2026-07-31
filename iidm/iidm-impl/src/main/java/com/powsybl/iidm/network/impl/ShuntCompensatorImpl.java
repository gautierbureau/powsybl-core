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

    // attributes depending on the variant

    // variant-dependent targetV / targetDeadband and the current / solved section counts are all held
    // columnarly (see NumericVariantStore). The section counts are nullable (unset); SECTION_NULL encodes
    // that in the int columns, exactly as TAP_NULL does for tap positions.
    private static final String STORE_KEY = "ShuntCompensator";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    static final int SECTION_NULL = Integer.MIN_VALUE;
    private static final int[] INT_DEFAULTS = {SECTION_NULL, SECTION_NULL};
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
        Integer checkedSolved = checkSolvedSectionCount(solvedSectionCount, model.getMaximumSectionCount());
        this.variantStore = network.get().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(
                new double[] {targetV, targetDeadband},
                new int[] {toRaw(sectionCount), toRaw(checkedSolved)},
                BOOLEAN_DEFAULTS);
        this.model = Objects.requireNonNull(model).attach(this);
    }

    @Override
    public TerminalExt getTerminal() {
        return terminals.get(0);
    }

    @Override
    public int getSectionCount() {
        int raw = rawSectionCount(COL_SECTION_COUNT);
        if (raw == SECTION_NULL) {
            throw ValidationUtil.createUndefinedValueGetterException();
        }
        return raw;
    }

    private int rawSectionCount(int column) {
        return variantStore.getInt(network.get().getVariantIndex(), column, variantStoreRow);
    }

    private static int toRaw(Integer value) {
        return value == null ? SECTION_NULL : value;
    }

    private static Integer fromRaw(int raw) {
        return raw == SECTION_NULL ? null : raw;
    }

    @Override
    public Integer getSolvedSectionCount() {
        return fromRaw(rawSectionCount(COL_SOLVED_SECTION_COUNT));
    }

    @Override
    public OptionalInt findSectionCount() {
        int raw = rawSectionCount(COL_SECTION_COUNT);
        return raw == SECTION_NULL ? OptionalInt.empty() : OptionalInt.of(raw);
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
        Integer oldValue = fromRaw(variantStore.setInt(variantIndex, COL_SECTION_COUNT, variantStoreRow, sectionCount));
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
        Integer oldValue = fromRaw(variantStore.setInt(variantIndex, COL_SECTION_COUNT, variantStoreRow, SECTION_NULL));
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate("sectionCount", variantId, oldValue, null);
        return this;
    }

    @Override
    public ShuntCompensatorImpl setSolvedSectionCount(int solvedSectionCount) {
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        Integer oldValue = fromRaw(variantStore.setInt(variantIndex, COL_SOLVED_SECTION_COUNT, variantStoreRow,
                toRaw(checkSolvedSectionCount(solvedSectionCount, this.model.getMaximumSectionCount()))));
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        notifyUpdate("solvedSectionCount", variantId, oldValue, solvedSectionCount);
        return this;
    }

    @Override
    public ShuntCompensator unsetSolvedSectionCount() {
        NetworkImpl n = getNetwork();
        int variantIndex = n.getVariantIndex();
        Integer oldValue = fromRaw(variantStore.setInt(variantIndex, COL_SOLVED_SECTION_COUNT, variantStoreRow, SECTION_NULL));
        String variantId = n.getVariantManager().getVariantId(variantIndex);
        notifyUpdate("solvedSectionCount", variantId, oldValue, null);
        return this;
    }

    @Override
    public double getB() {
        return model.getB(getSectionCount());
    }

    @Override
    public double getG() {
        return model.getG(getSectionCount());
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
        super.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
        // targetV / targetDeadband / section counts handled by NumericVariantStore
        regulatingPoint.extendVariantArraySize(initVariantArraySize, number, sourceIndex);
    }

    @Override
    public void reduceVariantArraySize(int number) {
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
        super.allocateVariantArrayElement(indexes, sourceIndex);
        regulatingPoint.allocateVariantArrayElement(indexes, sourceIndex);
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        super.reHomeVariantStores(targetNetwork);
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
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
