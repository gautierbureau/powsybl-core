/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A regulating point carries a regulating flag, a regulation mode, or both, depending on which constructor its
 * owner used — a generator or tap changer has the flag only, an AC/DC converter the mode only, a static var
 * compensator both. Asking for the attribute it does not have used to be a NullPointerException off a null
 * trove list; it must stay an error rather than silently reading a column holding a default.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class RegulatingPointAttributesTest {

    private NetworkImpl network;

    @BeforeEach
    void setUp() {
        network = (NetworkImpl) Network.create("test", "test");
    }

    private RegulatingPoint regulatingOnly(boolean regulating) {
        return new RegulatingPoint("EQ_REGULATING", () -> null, network.getRef(), regulating, true);
    }

    private RegulatingPoint modeOnly(int mode) {
        return new RegulatingPoint("EQ_MODE", () -> null, network.getRef(), mode, -1, false);
    }

    private RegulatingPoint both(int mode, boolean regulating) {
        return new RegulatingPoint("EQ_BOTH", () -> null, network.getRef(), mode, regulating, -1, true);
    }

    @Test
    void aRegulatingOnlyPointRejectsTheRegulationMode() {
        RegulatingPoint point = regulatingOnly(true);
        assertTrue(point.isRegulating(0));

        PowsyblException e = assertThrows(PowsyblException.class, () -> point.getRegulationMode(0));
        assertTrue(e.getMessage().contains("EQ_REGULATING"), e.getMessage());
        assertTrue(e.getMessage().contains("no regulation mode"), e.getMessage());
        assertThrows(PowsyblException.class, () -> point.setRegulationMode(0, 1));
    }

    @Test
    void aModeOnlyPointRejectsTheRegulatingFlag() {
        RegulatingPoint point = modeOnly(2);
        assertEquals(2, point.getRegulationMode(0));

        PowsyblException e = assertThrows(PowsyblException.class, () -> point.isRegulating(0));
        assertTrue(e.getMessage().contains("EQ_MODE"), e.getMessage());
        assertTrue(e.getMessage().contains("no regulating attribute"), e.getMessage());
        assertThrows(PowsyblException.class, () -> point.setRegulating(0, true));
    }

    @Test
    void aPointCarryingBothAnswersForBoth() {
        RegulatingPoint point = both(3, true);
        assertTrue(point.isRegulating(0));
        assertEquals(3, point.getRegulationMode(0));
        point.setRegulating(0, false);
        point.setRegulationMode(0, 4);
        assertFalse(point.isRegulating(0));
        assertEquals(4, point.getRegulationMode(0));
    }

    @Test
    void eachKindOnlyAllocatesTheColumnsItHas() {
        regulatingOnly(true);
        modeOnly(1);
        both(1, true);

        // the three kinds must not share a store, or the ones carrying a single attribute would pay for the
        // other's column - and getOrCreateNumericVariantStore would reject the mismatched layout outright
        NumericVariantStore regulating = network.getOrCreateNumericVariantStore(
                "RegulatingPoint.regulating", new double[] {}, new int[] {}, new boolean[] {false});
        NumericVariantStore mode = network.getOrCreateNumericVariantStore(
                "RegulatingPoint.mode", new double[] {}, new int[] {-1}, new boolean[] {});
        NumericVariantStore regulatingAndMode = network.getOrCreateNumericVariantStore(
                "RegulatingPoint.regulatingAndMode", new double[] {}, new int[] {-1}, new boolean[] {false});

        assertEquals(1, regulating.getRowCount());
        assertEquals(1, mode.getRowCount());
        assertEquals(1, regulatingAndMode.getRowCount());
    }
}
