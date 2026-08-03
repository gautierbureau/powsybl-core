/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.extensions.ControlVoltageLevel;
import com.powsybl.iidm.network.extensions.ControlVoltageLevelAdder;
import com.powsybl.iidm.network.extensions.MeasurementPoint;
import com.powsybl.iidm.network.extensions.MeasurementPointAdder;
import com.powsybl.iidm.network.extensions.TapChangerBlockingAdder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class TapChangerBlockingAdderImpl implements TapChangerBlockingAdder {

    private final TapChangerBlockingsAdderImpl parent;

    private String name;

    private final List<MeasurementPoint> measurementPoints = new ArrayList<>();

    private final List<ControlVoltageLevel> controlVoltageLevels = new ArrayList<>();

    TapChangerBlockingAdderImpl(TapChangerBlockingsAdderImpl parent) {
        this.parent = Objects.requireNonNull(parent);
    }

    public TapChangerBlockingsAdderImpl getParent() {
        return parent;
    }

    @Override
    public TapChangerBlockingAdderImpl withName(String name) {
        this.name = Objects.requireNonNull(name);
        return this;
    }

    void setMeasurementPoint(MeasurementPoint measurementPoint) {
        measurementPoints.add(Objects.requireNonNull(measurementPoint));
    }

    @Override
    public MeasurementPointAdder<TapChangerBlockingAdder> newMeasurementPoint() {
        return new MeasurementPointAdderTapChangerBlockingImpl(this);
    }

    void addControlVoltageLevel(ControlVoltageLevel controlVoltageLevel) {
        controlVoltageLevels.add(Objects.requireNonNull(controlVoltageLevel));
    }

    @Override
    public ControlVoltageLevelAdder<TapChangerBlockingAdder> newControlVoltageLevel() {
        return new ControlVoltageLevelAdderTapChangerBlockingImpl(this);
    }

    @Override
    public TapChangerBlockingsAdderImpl add() {
        if (name == null) {
            throw new PowsyblException("TapChangerBlocking name is not set");
        }
        if (measurementPoints.isEmpty()) {
            throw new PowsyblException("Empty measurement point list for tcb '" + name + "'");
        }
        if (controlVoltageLevels.isEmpty()) {
            throw new PowsyblException("Empty control voltage level list for tcb '" + name + "'");
        }
        TapChangerBlockingImpl tcb = new TapChangerBlockingImpl(name, measurementPoints, controlVoltageLevels);
        parent.addTapChangerBlocking(tcb);
        return parent;
    }
}
