/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

/**
 * Direct access to the two columnar variant stores that are hot enough to be worth reaching without a lookup
 * by key: terminal {@code p}/{@code q} and switch {@code open}/{@code retained}. They are ordinary
 * {@link NumericVariantStore}s, held in a field by the root network so that a getter or setter never pays for
 * the keyed map lookup {@link VariantManagerHolder#getOrCreateNumericVariantStore} does.
 *
 * <p>Kept off the public {@link VariantManagerHolder} on purpose: which stores the implementation happens to
 * keep a direct handle on is an internal detail, and nothing outside this package needs it.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
interface VariantStoreHolder extends VariantManagerHolder {

    /**
     * Network-level columnar store for the variant-dependent terminal {@code p}/{@code q}. Shared by every
     * terminal (including those in subnetworks), it is owned and structurally maintained by the root network.
     */
    NumericVariantStore getTerminalVariantStore();

    /**
     * Network-level columnar store for the variant-dependent switch topology ({@code open}/{@code retained}).
     * Shared by every switch, it is owned and structurally maintained by the root network.
     */
    NumericVariantStore getSwitchVariantStore();
}
