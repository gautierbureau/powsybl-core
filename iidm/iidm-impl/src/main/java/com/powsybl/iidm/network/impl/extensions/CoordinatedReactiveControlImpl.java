/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.extensions.CoordinatedReactiveControl;
import com.powsybl.iidm.network.impl.AbstractMultiVariantIdentifiableExtension;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Miora Ralambotiana {@literal <miora.ralambotiana at rte-france.com>}
 */
public class CoordinatedReactiveControlImpl extends AbstractMultiVariantIdentifiableExtension<Generator> implements CoordinatedReactiveControl {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoordinatedReactiveControlImpl.class);

    private static final String STORE_KEY = "CoordinatedReactiveControl";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_Q_PERCENT = 0;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    public CoordinatedReactiveControlImpl(Generator generator, double qPercent) {
        super(generator);
        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {checkQPercent(generator, qPercent)}, INT_DEFAULTS, BOOLEAN_DEFAULTS);
    }

    @Override
    public double getQPercent() {
        return variantStore.getDouble(getVariantIndex(), COL_Q_PERCENT, variantStoreRow);
    }

    @Override
    public void setQPercent(double qPercent) {
        variantStore.setDouble(getVariantIndex(), COL_Q_PERCENT, variantStoreRow, checkQPercent(getExtendable(), qPercent));
    }

    private static double checkQPercent(Generator generator, double qPercent) {
        if (Double.isNaN(qPercent)) {
            throw new PowsyblException(String.format("Undefined value (%s) for qPercent for generator %s",
                qPercent, generator.getId()));
        }
        if (qPercent < 0 || qPercent > 100) {
            LOGGER.debug("qPercent value of generator {} does not seem to be a valid percent: {}", generator.getId(), qPercent);
        }
        return qPercent;
    }

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        // handled by NumericVariantStore
    }

    @Override
    public void reduceVariantArraySize(int number) {
        // handled by NumericVariantStore
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        // Does nothing
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        // handled by NumericVariantStore
    }

    @Override
    public void reHomeVariantStores(NetworkImpl targetNetwork) {
        NumericVariantStore newStore = targetNetwork.getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = newStore.importRow(variantStore, variantStoreRow);
        this.variantStore = newStore;
    }
}
