/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.google.auto.service.AutoService;
import com.powsybl.commons.extensions.ExtensionAdderProvider;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.TapChangerBlockings;
import com.powsybl.iidm.network.extensions.TapChangerBlockingsAdder;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
@AutoService(ExtensionAdderProvider.class)
public class TapChangerBlockingsAdderImplProvider implements ExtensionAdderProvider<Network, TapChangerBlockings, TapChangerBlockingsAdder> {

    @Override
    public String getImplementationName() {
        return "Default";
    }

    @Override
    public String getExtensionName() {
        return TapChangerBlockings.NAME;
    }

    @Override
    public Class<? super TapChangerBlockingsAdder> getAdderClass() {
        return TapChangerBlockingsAdder.class;
    }

    @Override
    public TapChangerBlockingsAdderImpl newAdder(Network network) {
        return new TapChangerBlockingsAdderImpl(network);
    }
}
