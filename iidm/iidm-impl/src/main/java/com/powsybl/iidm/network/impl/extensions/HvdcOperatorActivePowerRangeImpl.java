/*
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.iidm.network.HvdcConverterStation;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.extensions.HvdcOperatorActivePowerRange;
import com.powsybl.iidm.network.impl.AbstractMultiVariantIdentifiableExtension;
import com.powsybl.iidm.network.impl.NetworkImpl;
import com.powsybl.iidm.network.impl.NumericVariantStore;

import java.util.Objects;

/**
 * @author Jérémy Labous {@literal <jlabous at silicom.fr>}
 * @author Paul Bui-Quang {@literal <paul.buiquang at rte-france.com>}
 */
public class HvdcOperatorActivePowerRangeImpl extends AbstractMultiVariantIdentifiableExtension<HvdcLine> implements HvdcOperatorActivePowerRange {

    // Operator active power ranges (MW) from CS1 to CS2 and from CS2 to CS1 are held columnarly in a
    // network-level NumericVariantStore (structure-of-arrays), so a variant clone copies them all at once.
    private static final String STORE_KEY = "HvdcOperatorActivePowerRange";
    private static final double[] DOUBLE_DEFAULTS = {Double.NaN, Double.NaN};
    private static final int[] INT_DEFAULTS = {};
    private static final boolean[] BOOLEAN_DEFAULTS = {};
    private static final int COL_OPR_CS1_TO_CS2 = 0;
    private static final int COL_OPR_CS2_TO_CS1 = 1;

    private NumericVariantStore variantStore;
    private int variantStoreRow;

    public HvdcOperatorActivePowerRangeImpl(HvdcLine hvdcLine, float oprFromCS1toCS2, float oprFromCS2toCS1) {
        super(hvdcLine);
        this.variantStore = getVariantManagerHolder().getOrCreateNumericVariantStore(STORE_KEY, DOUBLE_DEFAULTS, INT_DEFAULTS, BOOLEAN_DEFAULTS);
        this.variantStoreRow = variantStore.allocateRow(new double[] {
            checkOPR(oprFromCS1toCS2, hvdcLine.getConverterStation1(), hvdcLine.getConverterStation2()),
            checkOPR(oprFromCS2toCS1, hvdcLine.getConverterStation2(), hvdcLine.getConverterStation1())
        }, INT_DEFAULTS, BOOLEAN_DEFAULTS);
    }

    @Override
    public float getOprFromCS1toCS2() {
        return (float) variantStore.getDouble(getVariantIndex(), COL_OPR_CS1_TO_CS2, variantStoreRow);
    }

    @Override
    public HvdcOperatorActivePowerRangeImpl setOprFromCS1toCS2(float oprFromCS1toCS2) {
        variantStore.setDouble(getVariantIndex(), COL_OPR_CS1_TO_CS2, variantStoreRow,
                checkOPR(oprFromCS1toCS2, getExtendable().getConverterStation1(), getExtendable().getConverterStation2()));
        return this;
    }

    @Override
    public float getOprFromCS2toCS1() {
        return (float) variantStore.getDouble(getVariantIndex(), COL_OPR_CS2_TO_CS1, variantStoreRow);
    }

    @Override
    public HvdcOperatorActivePowerRangeImpl setOprFromCS2toCS1(float oprFromCS2toCS1) {
        variantStore.setDouble(getVariantIndex(), COL_OPR_CS2_TO_CS1, variantStoreRow,
                checkOPR(oprFromCS2toCS1, getExtendable().getConverterStation1(), getExtendable().getConverterStation2()));
        return this;
    }

    private float checkOPR(float opr, HvdcConverterStation<?> from, HvdcConverterStation<?> to) {
        if (!Float.isNaN(opr) && opr < 0) {
            String message = "OPR from " + from.getId() + " to " + to.getId() + " must be greater than 0 (current value " + Float.toString(opr) + ").";
            throw new IllegalArgumentException(message);
        }
        return opr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HvdcOperatorActivePowerRangeImpl that = (HvdcOperatorActivePowerRangeImpl) o;
        return Float.compare(that.getOprFromCS1toCS2(), getOprFromCS1toCS2()) == 0 &&
                Float.compare(that.getOprFromCS2toCS1(), getOprFromCS2toCS1()) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getOprFromCS1toCS2(), getOprFromCS2toCS1());
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
