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
     * Return the shared numeric columnar variant store for a given object type, creating and registering it on
     * first use. {@code key} identifies the type; the three defaults arrays give the number of columns of each
     * primitive type and the initial value of each column for a fresh row.
     *
     * <p>Objects address the store by column index, so every call for the same key must describe the same
     * columns. That is enforced: a later call whose defaults differ from the ones the store was created with
     * throws {@link IllegalStateException} rather than silently handing one type another type's columns.
     * Sharing a key deliberately is fine (ratio and phase tap changers do it) as long as the layouts match.</p>
     *
     * <p>Internal API: {@link NumericVariantStore} is an implementation detail of the columnar variant storage,
     * public only so that the extensions in {@code com.powsybl.iidm.network.impl.extensions} can hold a store
     * reference. It is not part of the supported iidm API and may change without notice. The stores whose types
     * do not need to cross a package boundary are declared on the package-private {@link VariantStoreHolder}
     * instead, so that this interface stays implementable from outside this package.</p>
     */
    NumericVariantStore getOrCreateNumericVariantStore(String key, double[] doubleDefaults, int[] intDefaults,
                                                       boolean[] booleanDefaults);

}
