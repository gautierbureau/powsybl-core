/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.extensions;

import com.powsybl.commons.extensions.Extension;
import com.powsybl.iidm.network.Network;

import java.util.List;
import java.util.Optional;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public interface TapChangerBlockings extends Extension<Network> {

    String NAME = "tcbs";

    List<TapChangerBlocking> getTapChangerBlockings();

    Optional<TapChangerBlocking> getTapChangerBlocking(String name);

    @Override
    default String getName() {
        return NAME;
    }
}
