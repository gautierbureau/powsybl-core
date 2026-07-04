/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

/**
 * A network-level columnar store of variant-dependent state (structure-of-arrays). The root network drives
 * the structural operations of every registered store once per variant operation, instead of every object
 * doing it for itself; see {@link NetworkImpl}. Structural operations run on the main thread only, per the
 * {@link com.powsybl.iidm.network.VariantManager} contract.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
interface VariantColumnStore {

    /** Append {@code number} variant bands, each a copy of the {@code sourceIndex} band. */
    void extend(int number, int sourceIndex);

    /** Drop the last {@code number} variant bands. */
    void reduce(int number);

    /** Release the band at {@code index} (an unused variant slot). */
    void delete(int index);

    /** Overwrite each band in {@code indexes} with a copy of the {@code sourceIndex} band. */
    void allocate(int[] indexes, int sourceIndex);
}
