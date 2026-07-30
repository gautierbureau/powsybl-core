/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

/**
 * Access to the network-level columnar variant stores whose types are package-private, and which are
 * therefore only usable from within this package.
 *
 * <p>This is deliberately kept separate from the public {@link VariantManagerHolder}: declaring these
 * accessors there would make that interface impossible to implement from outside this package, since an
 * implementor could not name the {@link TerminalVariantStore} / {@link SwitchVariantStore} return types.
 * The generic {@link NumericVariantStore} accessor stays on {@link VariantManagerHolder} because the
 * extensions in {@code com.powsybl.iidm.network.impl.extensions} need it.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
interface VariantStoreHolder extends VariantManagerHolder {

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
}
