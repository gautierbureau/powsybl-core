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

import java.util.Objects;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public abstract class AbstractControlVoltageLevelAdderImpl<T> implements ControlVoltageLevelAdder<T> {

    protected final T parent;

    protected String id;

    protected boolean forceOneTransformerLoads = false;

    protected AbstractControlVoltageLevelAdderImpl(T parent) {
        this.parent = Objects.requireNonNull(parent);
    }

    @Override
    public AbstractControlVoltageLevelAdderImpl<T> withId(String id) {
        this.id = Objects.requireNonNull(id);
        return this;
    }

    @Override
    public AbstractControlVoltageLevelAdderImpl<T> withForceOneTransformerLoads() {
        this.forceOneTransformerLoads = true;
        return this;
    }

    /**
     * Builds the controlled voltage level these settings describe, for an implementing adder to
     * hand to its own parent, so building it, and refusing one that names no level, is done once
     * here rather than in each extension that controls one.
     */
    protected ControlVoltageLevel buildControlVoltageLevel() {
        if (id == null) {
            throw new PowsyblException("Control voltage level ID is not set");
        }
        return new ControlVoltageLevelImpl(id, forceOneTransformerLoads);
    }
}
