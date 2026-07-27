/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.extensions.TapChangerBlockingAdder;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class MeasurementPointAdderTapChangerBlockingImpl extends AbstractMeasurementPointAdderImpl<TapChangerBlockingAdder> {

    MeasurementPointAdderTapChangerBlockingImpl(TapChangerBlockingAdderImpl parent) {
        super(parent);
    }

    @Override
    public TapChangerBlockingAdderImpl add() {
        if (buses.isEmpty() && busbarSectionIds.isEmpty()) {
            throw new PowsyblException("Measurement point references neither a bus nor a busbar section");
        }
        if (id == null) {
            throw new PowsyblException("Measurement point ID is not set");
        }
        ((TapChangerBlockingAdderImpl) parent).setMeasurementPoint(new MeasurementPointImpl(buses, busbarSectionIds, id));
        return (TapChangerBlockingAdderImpl) parent;
    }
}
