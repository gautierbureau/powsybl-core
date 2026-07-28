/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.*;

import java.util.*;
import java.util.function.Supplier;

/**
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class RatioTapChangerImpl extends AbstractTapChanger<RatioTapChangerParent, RatioTapChangerImpl, RatioTapChangerStepImpl> implements RatioTapChanger {

    private RatioTapChanger.RegulationMode regulationMode;

    RatioTapChangerImpl(RatioTapChangerParent parent, int lowTapPosition,
                        List<RatioTapChangerStepImpl> steps, TerminalExt regulationTerminal, boolean loadTapChangingCapabilities,
                        Integer tapPosition, Integer solvedTapPosition, Boolean regulating, RatioTapChanger.RegulationMode regulationMode, double regulationValue, double targetDeadband) {
        super(parent, lowTapPosition, steps, regulationTerminal, loadTapChangingCapabilities, tapPosition, solvedTapPosition, regulating, targetDeadband, regulationValue, "ratio tap changer");
        this.regulationMode = regulationMode;
    }

    protected void notifyUpdate(Supplier<String> attribute, Object oldValue, Object newValue) {
        getNetwork().getListeners().notifyUpdate(parent.getTransformer(), attribute, oldValue, newValue);
    }

    protected void notifyUpdate(Supplier<String> attribute, String variantId, Object oldValue, Object newValue) {
        getNetwork().getListeners().notifyUpdate(parent.getTransformer(), attribute, variantId, oldValue, newValue);
    }

    @Override
    protected RegulatingPoint createRegulatingPoint(boolean regulating) {
        return new RegulatingPoint(parent.getTransformer().getId(), () -> null, network, regulating, true);
    }

    @Override
    protected Integer getRelativeNeutralPosition() {
        for (int i = 0; i < steps.size(); i++) {
            RatioTapChangerStepImpl step = steps.get(i);
            if (step.getRho() == 1) {
                return i;
            }
        }
        return null;
    }

    @Override
    public RatioTapChangerStepsReplacerImpl stepsReplacer() {
        return new RatioTapChangerStepsReplacerImpl(this);
    }

    @Override
    public Optional<RatioTapChangerStep> getNeutralStep() {
        return relativeNeutralPosition != null ? Optional.of(steps.get(relativeNeutralPosition)) : Optional.empty();
    }

    @Override
    public RatioTapChangerImpl setRegulating(boolean regulating) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkRatioTapChangerRegulation(parent, regulating, loadTapChangingCapabilities,
                regulatingPoint.getRegulatingTerminal(), getRegulationMode(), getRegulationValue(), n,
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        Set<TapChanger<?, ?, ?, ?>> tapChangers = new HashSet<>(parent.getAllTapChangers());
        tapChangers.remove(parent.getRatioTapChanger());
        ValidationUtil.checkOnlyOneTapChangerRegulatingEnabled(parent, tapChangers, regulating,
                n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        n.invalidateValidationLevel();
        return super.setRegulating(regulating);
    }

    @Override
    public boolean hasLoadTapChangingCapabilities() {
        return loadTapChangingCapabilities;
    }

    @Override
    public RatioTapChangerImpl setLoadTapChangingCapabilities(boolean loadTapChangingCapabilities) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkRatioTapChangerRegulation(parent, isRegulating(), loadTapChangingCapabilities,
                regulatingPoint.getRegulatingTerminal(), getRegulationMode(),
                getRegulationValue(), n, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        boolean oldValue = this.loadTapChangingCapabilities;
        this.loadTapChangingCapabilities = loadTapChangingCapabilities;
        n.invalidateValidationLevel();
        notifyUpdate(() -> getTapChangerAttribute() + ".loadTapChangingCapabilities", oldValue, loadTapChangingCapabilities);
        return this;
    }

    @Override
    public double getTargetV() {
        if (regulationMode != RegulationMode.VOLTAGE) {
            return Double.NaN;
        }
        return getRegulationValue();
    }

    @Override
    public RatioTapChangerImpl setTargetV(double targetV) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkRatioTapChangerRegulation(parent, isRegulating(), loadTapChangingCapabilities, regulatingPoint.getRegulatingTerminal(),
                RegulationMode.VOLTAGE, targetV, n, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        int variantIndex = network.get().getVariantIndex();
        double oldRegulationValue = variantStore.setDouble(variantIndex, COL_REGULATION_VALUE, variantStoreRow, targetV);
        RatioTapChanger.RegulationMode oldRegulationMode = this.regulationMode;
        if (!Double.isNaN(targetV)) {
            regulationMode = RegulationMode.VOLTAGE;
        }
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate(() -> getTapChangerAttribute() + ".regulationValue", variantId, oldRegulationValue, targetV);
        notifyUpdate(() -> getTapChangerAttribute() + ".regulationMode", oldRegulationMode, regulationMode);
        return this;
    }

    @Override
    public RatioTapChanger.RegulationMode getRegulationMode() {
        return regulationMode;
    }

    @Override
    public RatioTapChangerImpl setRegulationMode(RatioTapChanger.RegulationMode regulationMode) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkRatioTapChangerRegulation(parent, isRegulating(), loadTapChangingCapabilities,
                regulatingPoint.getRegulatingTerminal(), regulationMode,
                getRegulationValue(), n, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        RatioTapChanger.RegulationMode oldValue = this.regulationMode;
        this.regulationMode = regulationMode;
        n.invalidateValidationLevel();
        notifyUpdate(() -> getTapChangerAttribute() + ".regulationMode", oldValue, regulationMode);
        return this;
    }

    @Override
    public double getRegulationValue() {
        return variantStore.getDouble(network.get().getVariantIndex(), COL_REGULATION_VALUE, variantStoreRow);
    }

    @Override
    public RatioTapChangerImpl setRegulationValue(double regulationValue) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkRatioTapChangerRegulation(parent, isRegulating(), loadTapChangingCapabilities,
                regulatingPoint.getRegulatingTerminal(), getRegulationMode(), regulationValue,
                n, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        int variantIndex = network.get().getVariantIndex();
        double oldValue = variantStore.setDouble(variantIndex, COL_REGULATION_VALUE, variantStoreRow, regulationValue);
        String variantId = network.get().getVariantManager().getVariantId(variantIndex);
        n.invalidateValidationLevel();
        notifyUpdate(() -> getTapChangerAttribute() + ".regulationValue", variantId, oldValue, regulationValue);
        return this;
    }

    @Override
    public RatioTapChangerImpl setRegulationTerminal(Terminal regulationTerminal) {
        NetworkImpl n = getNetwork();
        ValidationUtil.checkRatioTapChangerRegulation(parent, isRegulating(), loadTapChangingCapabilities, regulationTerminal,
                getRegulationMode(), getRegulationValue(), n, n.getMinValidationLevel(), n.getReportNodeContext().getReportNode());
        n.invalidateValidationLevel();
        return super.setRegulationTerminal(regulationTerminal);
    }

    @Override
    public void remove() {
        super.remove();
        parent.setRatioTapChanger(null);
    }

    @Override
    protected String getTapChangerAttribute() {
        return "ratio" + parent.getTapChangerAttribute();
    }

    @Override
    public Map<Integer, RatioTapChangerStep> getAllSteps() {
        Map<Integer, RatioTapChangerStep> allSteps = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            allSteps.put(i + lowTapPosition, steps.get(i));
        }
        return allSteps;
    }
}
