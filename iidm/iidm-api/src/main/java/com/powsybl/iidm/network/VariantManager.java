/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network;

import java.util.Collection;
import java.util.List;

/**
 * This class provides methods to manage variants of the network (create and
 * remove a variant, set the working variant, etc).
 * <p>
 * A variant is a branch of the network: cloning one takes a snapshot of its source, and everything written
 * afterwards — both the per-variant state values (setpoints, tap positions, switch open, terminal p/q) and
 * the network structure (which objects exist, and which voltage level their terminals sit on) — is scoped to
 * the variant it was written in. A variant never sees a change made in another variant, nor a change made in
 * its source after the clone. There is a single kind of variant: callers do not choose, and do not need to
 * know, how an implementation stores the divergence.
 * <p>
 * WARNING: Variant management is not thread safe and should never be done will an other thread read from or write to
 * an already existing variant. The classical pattern for multi-variant processing is to pre-allocate variants and
 * call {@link #allowVariantMultiThreadAccess} to allow multi-thread access on main thread, work on variants from other
 * threads (be carefull to only write concurrently attributes flagged as dependent to variant in the Javadoc) and then
 * remove variants from main thread once work is over.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface VariantManager {

    /**
     * Get the variant id list.
     *
     * @return the variant id list
     */
    Collection<String> getVariantIds();

    /**
     * Get the working variant.
     *
     * @return the id of the working variant
     */
    String getWorkingVariantId();

    /**
     * Set the working variant.
     *
     * @param variantId the id of the working variant
     * @throws com.powsybl.commons.PowsyblException if the variant is not found
     */
    void setWorkingVariant(String variantId);

    /**
     * Create a new variant by cloning an existing one.
     *
     * @param sourceVariantId the source variant id
     * @param targetVariantIds the target variant id list (the ones that will be created)
     * @throws com.powsybl.commons.PowsyblException
     *                         if the source variant is not found or if a variant with
     *                         an id of targetStateIds already exists
     */
    void cloneVariant(String sourceVariantId, List<String> targetVariantIds);

    /**
     * Create or overwrite a variant by cloning an existing one.
     *
     * @param sourceVariantId the source variant id
     * @param targetVariantIds the target variant id list (the ones that will be created/overwritten)
     * @param mayOverwrite indicates if the target can be overwritten when it already exists
     * @throws com.powsybl.commons.PowsyblException
     *                       if a variant with an id of targetVariantIds already exists and
     *                       the mayOverwrite parameter is set to {@code false}
     */
    void cloneVariant(String sourceVariantId, List<String> targetVariantIds, boolean mayOverwrite);

    /**
     * Create a new variant by cloning an existing one.
     *
     * @param sourceVariantId the source variant id
     * @param targetVariantId the target variant id (the one that will be created)
     * @throws com.powsybl.commons.PowsyblException
     *                         if the source variant is not found or if a variant with
     *                         the id targetVariantId already exists
     */
    void cloneVariant(String sourceVariantId, String targetVariantId);

    /**
     * Create or overwrite a variant by cloning an existing one.
     *
     * @param sourceVariantId the source variant id
     * @param targetVariantId the target variant id list (the one that will be created/overwritten)
     * @param mayOverwrite indicates if the target can be overwritten when it already exists
     * @throws com.powsybl.commons.PowsyblException
     *      *                         if a variant with the id of targetVariantId already exists and
     *      *                         the mayOverwrite parameter is set to {@code false}
     */
    void cloneVariant(String sourceVariantId, String targetVariantId, boolean mayOverwrite);

    /**
     * Pre-allocate storage capacity for {@code number} additional variants, without creating them.
     * <p>
     * This is a <b>performance hint, not a requirement</b>. Creating a variant with the {@code cloneVariant}
     * methods is safe to call concurrently from several threads while
     * {@link #allowVariantMultiThreadAccess(boolean)} is enabled, whether or not capacity was reserved first.
     * Reserving it simply lets those creations reuse an already-sized slot instead of growing the underlying
     * per-variant storage, so it trades memory for avoiding that work inside the parallel region. Reads and
     * writes of variant-dependent attributes stay concurrent either way, each thread on its own variant.
     * <p>
     * While multi-thread access is enabled the per-variant storage is never shrunk, so removing a variant
     * frees its slot for reuse but keeps the capacity.
     * <p>
     * Call this on the main thread, once the network structure is complete and before enabling multi-thread
     * access. Reserved slots that are never used cost only memory. The default implementation does nothing,
     * which is a valid implementation of a hint.
     *
     * @param number the number of additional variant slots to reserve (must be {@code >= 0})
     */
    default void preAllocateVariants(int number) {
        // no-op by default: implementations supporting thread-safe on-demand variant creation override this
    }

    /**
     * Remove a variant.
     *
     * @param variantId the id of the variant to remove
     */
    void removeVariant(String variantId);

    /**
     * Allows variants to be accessed simultaneously by different threads. When
     * this option is activated, the working variant can have a different value
     * for each thread.
     * @param allow
     */
    void allowVariantMultiThreadAccess(boolean allow);

    /**
     * Get the allowed multithread access state .
     *
     * @return a boolean to check if the variantManager is allowed to be accessed
     * simulaneously by different threads.
     */
    boolean isVariantMultiThreadAccessAllowed();
}
