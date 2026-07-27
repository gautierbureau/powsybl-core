/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.extensions.AbstractExtension;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.TapChangerBlocking;
import com.powsybl.iidm.network.extensions.TapChangerBlockings;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class TapChangerBlockingsImpl extends AbstractExtension<Network> implements TapChangerBlockings {

    private final List<TapChangerBlocking> tcbs;

    TapChangerBlockingsImpl(List<TapChangerBlocking> tcbs) {
        this.tcbs = Objects.requireNonNull(tcbs);
    }

    @Override
    public List<TapChangerBlocking> getTapChangerBlockings() {
        return Collections.unmodifiableList(tcbs);
    }

    @Override
    public Optional<TapChangerBlocking> getTapChangerBlocking(String name) {
        Objects.requireNonNull(name);
        return tcbs.stream().filter(z -> z.getName().equals(name)).findFirst();
    }
}
