/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.extensions.MeasurementPoint;
import com.powsybl.iidm.network.extensions.MeasurementPointAdder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public abstract class AbstractMeasurementPointAdderImpl<T> implements MeasurementPointAdder<T> {

    protected final T parent;

    protected List<MeasurementPoint.BusRef> buses = new ArrayList<>();

    protected List<String> busbarSectionIds = new ArrayList<>();

    protected String id;

    protected AbstractMeasurementPointAdderImpl(T parent) {
        this.parent = Objects.requireNonNull(parent);
    }

    @Override
    public AbstractMeasurementPointAdderImpl<T> withBuses(List<MeasurementPoint.BusRef> buses) {
        this.buses = Objects.requireNonNull(buses);
        return this;
    }

    @Override
    public AbstractMeasurementPointAdderImpl<T> withBusbarSectionIds(List<String> busbarSectionIds) {
        this.busbarSectionIds = Objects.requireNonNull(busbarSectionIds);
        return this;
    }

    @Override
    public AbstractMeasurementPointAdderImpl<T> withId(String id) {
        this.id = id;
        return this;
    }

    /**
     * Builds the measurement point these settings describe, for an implementing adder to hand to
     * its own parent. The point is shared between the extensions watching one — a tap changer
     * blocking, an ACMC, a SMACC — so building it, and refusing one that names nothing, is done once
     * here rather than in each.
     */
    protected MeasurementPoint buildMeasurementPoint() {
        if (buses.isEmpty() && busbarSectionIds.isEmpty()) {
            throw new PowsyblException("Measurement point references neither a bus nor a busbar section");
        }
        if (id == null) {
            throw new PowsyblException("Measurement point ID is not set");
        }
        return new MeasurementPointImpl(buses, busbarSectionIds, id);
    }
}
