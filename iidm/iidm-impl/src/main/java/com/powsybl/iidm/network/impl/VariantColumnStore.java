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

    /**
     * Append {@code number} variant bands inheriting from the {@code sourceIndex} band through the variant
     * parentage ({@link VariantCowState}). This is O(1): the new bands own no rows and resolve reads through
     * the parentage until they diverge.
     */
    void extend(int number, int sourceIndex);

    /** Drop the last {@code number} variant bands. */
    void reduce(int number);

    /** Release the band at {@code index} (an unused variant slot). */
    void delete(int index);

    /**
     * Re-purpose each (recycled or overwritten) band in {@code indexes} as a copy-on-write band inheriting
     * from {@code sourceIndex}. O(1) per band.
     */
    void allocate(int[] indexes, int sourceIndex);

    /**
     * Freeze the full resolved band of {@code variantIndex} into every copy-on-write variant that still
     * inherits from it, before the band is removed or overwritten (so a child variant forked earlier keeps
     * its snapshot).
     */
    void materializeInheritors(int variantIndex);
}
