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
import java.util.Objects;

/**
 * This class provides methods to manage variants of the network (create and
 * remove a variant, set the working variant, etc).
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
     * The capability a cloned variant has to diverge from its source. Mirrors network-store's
     * <em>full</em> vs <em>partial</em> variant distinction. See {@code structural-variant-public-api.md}.
     */
    enum VariantCloneStrategy {

        /**
         * Current behaviour (default): the target variant gets an independent copy of every per-variant
         * <b>state</b> value (setpoints, tap positions, switch open, terminal p/q) and shares the network
         * <b>structure</b> with all other variants. Structural changes made while it is active are
         * network-wide.
         */
        STATE_ONLY,

        /**
         * The target variant may diverge <b>structurally</b>: objects can be added, removed, or
         * re-connected in it without affecting any other variant. For a network that has at least one
         * structural variant, object existence and topology are resolved against the active variant.
         */
        STRUCTURAL
    }

    /**
     * Create a new variant by cloning an existing one, with an explicit clone strategy.
     *
     * <p>{@link VariantCloneStrategy#STATE_ONLY} is the historical behaviour and the default of every
     * other {@code cloneVariant} overload. {@link VariantCloneStrategy#STRUCTURAL} additionally lets the
     * target variant diverge structurally (see the enum and {@code structural-variant-public-api.md}).</p>
     *
     * @param sourceVariantId the source variant id
     * @param targetVariantId the target variant id (the one that will be created)
     * @param strategy        the clone strategy
     */
    default void cloneVariant(String sourceVariantId, String targetVariantId, VariantCloneStrategy strategy) {
        cloneVariant(sourceVariantId, List.of(targetVariantId), strategy, false);
    }

    /**
     * Create or overwrite variants by cloning an existing one, with an explicit clone strategy.
     *
     * @param sourceVariantId  the source variant id
     * @param targetVariantIds the target variant id list
     * @param strategy         the clone strategy
     * @param mayOverwrite     indicates if a target can be overwritten when it already exists
     */
    default void cloneVariant(String sourceVariantId, List<String> targetVariantIds, VariantCloneStrategy strategy, boolean mayOverwrite) {
        Objects.requireNonNull(strategy);
        if (strategy == VariantCloneStrategy.STATE_ONLY) {
            cloneVariant(sourceVariantId, targetVariantIds, mayOverwrite);
        } else {
            throw new UnsupportedOperationException(
                    "STRUCTURAL variant clone is a proposed API not yet implemented; see structural-variant-public-api.md");
        }
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
