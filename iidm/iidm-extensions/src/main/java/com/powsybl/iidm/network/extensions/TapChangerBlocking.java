/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.extensions;

import java.util.List;
import java.util.Optional;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public interface TapChangerBlocking {

    String getName();

    List<MeasurementPoint> getMeasurementPoints();

    Optional<MeasurementPoint> getMeasurementPoint(String id);

    List<ControlVoltageLevel> getControlVoltageLevels();

    Optional<ControlVoltageLevel> getControlVoltageLevel(String id);
}
