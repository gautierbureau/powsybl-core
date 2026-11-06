/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.extensions.ControlUnitAdder;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ControlUnitAdderImpl implements ControlUnitAdder {

    private final ControlZoneAdderImpl parent;

    private String id;

    private boolean participate = true;

    private double participationFactor = Double.NaN;

    ControlUnitAdderImpl(ControlZoneAdderImpl parent) {
        this.parent = Objects.requireNonNull(parent);
    }

    @Override
    public ControlUnitAdderImpl withId(String id) {
        this.id = Objects.requireNonNull(id);
        return this;
    }

    @Override
    public ControlUnitAdderImpl withParticipate(boolean participate) {
        this.participate = participate;
        return this;
    }

    @Override
    public ControlUnitAdderImpl withParticipationFactor(double participationFactor) {
        this.participationFactor = participationFactor;
        return this;
    }

    @Override
    public ControlZoneAdderImpl add() {
        if (id == null) {
            throw new PowsyblException("Control unit ID is not set");
        }
        if (!Double.isNaN(participationFactor) && participationFactor <= 0) {
            throw new PowsyblException("Control unit '" + id + "' participation factor must be positive: " + participationFactor);
        }
        parent.addControlUnit(new ControlUnitImpl(id, participate, participationFactor, parent.getParent().getNetwork()));
        return parent;
    }
}
