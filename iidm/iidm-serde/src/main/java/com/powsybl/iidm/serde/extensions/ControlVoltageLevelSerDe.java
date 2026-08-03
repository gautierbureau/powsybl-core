/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.serde.extensions;

import com.powsybl.commons.io.DeserializerContext;
import com.powsybl.commons.io.TreeDataReader;
import com.powsybl.commons.io.TreeDataWriter;
import com.powsybl.iidm.network.extensions.ControlVoltageLevel;
import com.powsybl.iidm.network.extensions.ControlVoltageLevelAdder;

/**
 * Reads and writes the body of a controlled voltage level — its id and whether it forces its loads
 * onto one transformer — kept in one place because it is shared between the extensions that control
 * one, and serialized the same for each as {@code <... forceOneTransformerLoads="true">vlId</...>}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class ControlVoltageLevelSerDe {

    private static final String FORCE_ONE_TRANSFORMER_LOADS_ATTRIBUTE = "forceOneTransformerLoads";

    private ControlVoltageLevelSerDe() {
    }

    /**
     * Writes the id and the force one transformer loads flag of a level, the enclosing element
     * already opened by the caller under whichever name the extension gives it.
     */
    public static void writeBody(ControlVoltageLevel controlVoltageLevel, TreeDataWriter writer) {
        if (controlVoltageLevel.forceOneTransformerLoads()) {
            writer.writeStringAttribute(FORCE_ONE_TRANSFORMER_LOADS_ATTRIBUTE, "true");
        }
        writer.writeNodeContent(controlVoltageLevel.getId());
    }

    /**
     * Reads the id and the flag of a level into an adder and adds it, the adder's parent given by
     * the caller so the level lands where it is controlled.
     */
    public static void readBody(ControlVoltageLevelAdder<?> adder, DeserializerContext context) {
        TreeDataReader reader = context.getReader();
        boolean forceOneTransformerLoads = reader.readBooleanAttribute(FORCE_ONE_TRANSFORMER_LOADS_ATTRIBUTE, false);
        adder.withId(reader.readContent());
        if (forceOneTransformerLoads) {
            adder.withForceOneTransformerLoads();
        }
        adder.add();
    }
}
