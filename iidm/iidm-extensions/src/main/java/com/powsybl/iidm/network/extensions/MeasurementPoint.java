/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.extensions;

import java.util.List;
import java.util.Objects;

/**
 * A point whose voltage a tap changer blocking automaton watches, given as the buses and busbar
 * sections it is measured at.
 * <p>
 * Buses and busbar sections are kept apart rather than in one list of ids: a bus of the bus/breaker
 * view is named by its voltage level and its id, a busbar section by its id alone, so the two are
 * told from each other rather than guessed at from the network they resolve against.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public interface MeasurementPoint {

    /**
     * A bus of the bus/breaker view, named by its voltage level id and its bus id.
     */
    record BusRef(String voltageLevelId, String busId) {

        public BusRef {
            Objects.requireNonNull(voltageLevelId, "Measurement point bus voltage level ID is null");
            Objects.requireNonNull(busId, "Measurement point bus ID is null");
        }
    }

    /**
     * The buses of the bus/breaker view the point is measured at.
     */
    List<BusRef> getBuses();

    /**
     * The busbar sections the point is measured at.
     */
    List<String> getBusbarSectionIds();

    String getId();
}
