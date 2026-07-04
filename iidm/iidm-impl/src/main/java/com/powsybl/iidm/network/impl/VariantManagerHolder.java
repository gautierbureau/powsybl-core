/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

/**
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface VariantManagerHolder {

    VariantManagerImpl getVariantManager();

    int getVariantIndex();

    /**
     * Network-level columnar store for the variant-dependent terminal {@code p}/{@code q}. Shared by every
     * terminal (including those in subnetworks), it is owned and structurally maintained by the root network.
     */
    TerminalVariantStore getTerminalVariantStore();

    /**
     * Network-level columnar store for the variant-dependent switch topology ({@code open}/{@code retained}).
     * Shared by every switch, it is owned and structurally maintained by the root network.
     */
    SwitchVariantStore getSwitchVariantStore();

    /** Network-level columnar store for node-terminal v / angle / connected &amp; synchronous component. */
    NumericVariantStore getNodeTerminalVariantStore();

    /** Network-level columnar store for configured-bus v / angle / fictitious P0/Q0 / component numbers. */
    NumericVariantStore getConfiguredBusVariantStore();

}
