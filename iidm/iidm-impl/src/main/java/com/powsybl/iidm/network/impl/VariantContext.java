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
interface VariantContext {

    int getVariantIndex();

    void setVariantIndex(int index);

    void resetIfVariantIndexIs(int index);

    boolean isIndexSet();

    /**
     * Whether more than one thread has bound a working variant on this context, i.e. whether the network is
     * actually being accessed concurrently rather than merely allowed to be. Enabling multi-thread access is
     * a declaration of intent; this reports the fact. Always {@code false} for a single-threaded context.
     */
    default boolean isSharedAcrossThreads() {
        return false;
    }

}
