/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.serde.test;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.iidm.serde.ImportOptions;
import com.powsybl.iidm.serde.NetworkSerDe;

/**
 * The Nordic 32 test system, the network alone: the buses, lines, transformers, generators and
 * loads, with none of the dynamic characteristics a study describes on top of them.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class Nordic32Factory {

    private Nordic32Factory() {
    }

    public static Network create() {
        return create(NetworkFactory.findDefault());
    }

    public static Network create(NetworkFactory networkFactory) {
        return NetworkSerDe.read(Nordic32Factory.class.getResourceAsStream("/nordic32.xiidm"),
                new ImportOptions(), null, networkFactory, ReportNode.NO_OP);
    }
}
