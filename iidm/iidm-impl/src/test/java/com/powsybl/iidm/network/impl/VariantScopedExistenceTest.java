/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests variant-scoped existence ({@link VariantScopedExistence}). See
 * {@code structural-variant-public-api.md}.
 *
 * <p>On a <b>single network</b>, object existence is a function of the active variant: a structural
 * variant is a cloned variant, and the working variant <em>is</em> the context. The existence column
 * rides the real {@code cloneVariant} — cloning a variant clones existence exactly as it clones state
 * (tap positions, switch open, terminal p/q) — the network-store {@code fullVariantNum} property realised
 * in-place.</p>
 *
 * @author Claude
 */
class VariantScopedExistenceTest {

    private static NetworkImpl buildBase() {
        Network n = Network.create("base", "test");
        Substation sa = n.newSubstation().setId("SA").add();
        VoltageLevel vla = sa.newVoltageLevel().setId("VLA").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vla.getBusBreakerView().newBus().setId("busA").add();
        Substation sb = n.newSubstation().setId("SB").add();
        VoltageLevel vlb = sb.newVoltageLevel().setId("VLB").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlb.getBusBreakerView().newBus().setId("busB").add();
        n.newLine().setId("L").setVoltageLevel1("VLA").setConnectableBus1("busA").setBus1("busA")
                .setVoltageLevel2("VLB").setConnectableBus2("busB").setBus2("busB")
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
        return (NetworkImpl) n;
    }

    @Test
    void existenceIsScopedToTheWorkingVariant() {
        NetworkImpl n = buildBase();
        VariantScopedExistence existence = n.enableVariantScopedExistence();

        // a structural "branch" is just a cloned variant of the SAME network
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "faulted");

        // in the faulted variant, hide the line (a variant-scoped structural tombstone)
        n.getVariantManager().setWorkingVariant("faulted");
        existence.hideInCurrentVariant(n.getLine("L"));

        // faulted view: L does not exist — via getLine, getIdentifiable, contains and the counts
        assertNull(n.getLine("L"));
        assertNull(n.getIdentifiable("L"));
        assertFalse(n.getIndex().contains("L"));
        assertEquals(0, n.getLineCount());

        // the initial variant is untouched: the very same network still has L
        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertNotNull(n.getLine("L"));
        assertTrue(n.getIndex().contains("L"));
        assertEquals(1, n.getLineCount());
    }

    @Test
    void existenceIsClonedByTheRealVariantClone() {
        NetworkImpl n = buildBase();
        VariantScopedExistence existence = n.enableVariantScopedExistence();
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "faulted");
        n.getVariantManager().setWorkingVariant("faulted");
        existence.hideInCurrentVariant(n.getLine("L"));

        // clone the faulted variant: the real cloneVariant must copy the existence column, so the child
        // inherits the hidden line — existence is variant state, grown/copied like every other column.
        n.getVariantManager().cloneVariant("faulted", "faulted2");
        n.getVariantManager().setWorkingVariant("faulted2");
        assertNull(n.getLine("L"));

        // and a clone of the pristine initial variant does NOT inherit the hide
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "scenario");
        n.getVariantManager().setWorkingVariant("scenario");
        assertNotNull(n.getLine("L"));
    }

    @Test
    void showRestoresExistenceInTheWorkingVariantOnly() {
        NetworkImpl n = buildBase();
        VariantScopedExistence existence = n.enableVariantScopedExistence();
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "faulted");
        n.getVariantManager().setWorkingVariant("faulted");
        com.powsybl.iidm.network.Line line = n.getLine("L");
        existence.hideInCurrentVariant(line);
        assertNull(n.getLine("L"));

        existence.showInCurrentVariant(line);
        assertNotNull(n.getLine("L"));
    }
}
