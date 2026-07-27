/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.iidm.network.extensions.ControlVoltageLevel;

import java.util.Objects;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ControlVoltageLevelImpl implements ControlVoltageLevel {

    private final String id;

    private boolean forceOneTransformerLoads;

    ControlVoltageLevelImpl(String id) {
        this.id = Objects.requireNonNull(id);
    }

    ControlVoltageLevelImpl(String id, boolean forceOneTransformerLoads) {
        this.id = Objects.requireNonNull(id);
        this.forceOneTransformerLoads = forceOneTransformerLoads;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean forceOneTransformerLoads() {
        return forceOneTransformerLoads;
    }
}
