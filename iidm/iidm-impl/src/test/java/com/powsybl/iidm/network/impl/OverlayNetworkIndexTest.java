/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural-variant spike: proves that a copy-on-write overlay over a shared, read-only
 * {@link NetworkIndex} can represent the "split a line, insert a mid-line voltage level" change as a
 * small delta — with the branch reflecting the split while the shared base is untouched, and memory
 * proportional to the delta, not the network.
 *
 * <p>This is the registry-level proof of feasibility. Wiring the overlay into {@code NetworkImpl} so
 * the branch is a fully traversable {@code Network} is spike phase 2 (see
 * {@code structural-variant-spike.md}). Here the branch-local objects are minted in a donor network,
 * because the index only stores object references by id/class.</p>
 *
 * @author Claude
 */
class OverlayNetworkIndexTest {

    private static NetworkIndex indexOf(Network n) {
        return ((NetworkImpl) n).getIndex();
    }

    @Test
    void pristineOverlayIsAViewOfTheBase() {
        Network base = EurostagTutorialExample1Factory.create();
        NetworkIndex baseIndex = indexOf(base);
        OverlayNetworkIndex overlay = new OverlayNetworkIndex(baseIndex);

        assertTrue(overlay.isPristine());
        assertEquals(0, overlay.deltaSize());
        // reads fall through to the shared base, same instances
        assertSame(baseIndex.get("NHV1_NHV2_1"), overlay.get("NHV1_NHV2_1"));
        assertTrue(overlay.contains("NHV1_NHV2_2"));
        assertEquals(baseIndex.getAll(LineImpl.class).size(), overlay.getAll(LineImpl.class).size());
    }

    @Test
    void splitLineIsIsolatedInTheBranchAndBaseIsUntouched() {
        // Base network: the study reference. It must never change.
        Network base = EurostagTutorialExample1Factory.create();
        NetworkIndex baseIndex = indexOf(base);
        Line originalLine = (Line) baseIndex.get("NHV1_NHV2_1");
        assertNotNull(originalLine);

        // Branch = overlay over the shared base. No network copy.
        OverlayNetworkIndex branch = new OverlayNetworkIndex(baseIndex);

        // The fictitious mid-line VL and the two half-lines that replace the original line.
        SplitObjects split = mintSplitObjects();

        // Apply the split *to the branch only*: hide the original line, add VLf + the two halves.
        branch.tombstone(originalLine);
        branch.add(split.fictVl);
        branch.add(split.half1);
        branch.add(split.half2);

        // --- the branch reflects the split ---
        assertNull(branch.get("NHV1_NHV2_1"), "original line hidden in the branch");
        assertSame(split.fictVl, branch.get("FICT_VL"));
        assertSame(split.half1, branch.get("L_1"));
        assertSame(split.half2, branch.get("L_2"));
        Set<LineImpl> branchLines = branch.getAll(LineImpl.class);
        assertTrue(branchLines.contains(split.half1) && branchLines.contains(split.half2));
        assertFalse(branchLines.contains(originalLine), "split line no longer among the branch's lines");
        // NHV1_NHV2_1 replaced by L_1 + L_2 => same line count in the branch as in the base
        assertEquals(baseIndex.getAll(LineImpl.class).size() + 1, branchLines.size());

        // --- the shared base is completely untouched ---
        assertSame(originalLine, baseIndex.get("NHV1_NHV2_1"));
        assertNull(baseIndex.get("FICT_VL"));
        assertNull(baseIndex.get("L_1"));
        assertNotNull(base.getLine("NHV1_NHV2_1"));
        assertNull(base.getVoltageLevel("FICT_VL"));

        // --- footprint is O(delta): 1 tombstone + 3 additions, regardless of network size ---
        assertEquals(4, branch.deltaSize());
    }

    @Test
    void twoBranchesOverTheSameBaseAreIndependent() {
        Network base = EurostagTutorialExample1Factory.create();
        NetworkIndex baseIndex = indexOf(base);
        Line line = (Line) baseIndex.get("NHV1_NHV2_1");

        OverlayNetworkIndex branchA = new OverlayNetworkIndex(baseIndex);
        OverlayNetworkIndex branchB = new OverlayNetworkIndex(baseIndex);

        SplitObjects split = mintSplitObjects();
        branchA.tombstone(line);
        branchA.add(split.fictVl);

        // branch A sees the split; branch B and the base do not
        assertNull(branchA.get("NHV1_NHV2_1"));
        assertNotNull(branchB.get("NHV1_NHV2_1"));
        assertNull(branchB.get("FICT_VL"));
        assertTrue(branchB.isPristine());
    }

    @Test
    void reAddingATombstonedIdResurrectsItAsBranchLocal() {
        Network base = EurostagTutorialExample1Factory.create();
        NetworkIndex baseIndex = indexOf(base);
        Line line = (Line) baseIndex.get("NHV1_NHV2_1");

        OverlayNetworkIndex branch = new OverlayNetworkIndex(baseIndex);
        branch.tombstone(line);
        assertNull(branch.get("NHV1_NHV2_1"));
        branch.add(line);
        assertSame(line, branch.get("NHV1_NHV2_1"));
        assertEquals(1, branch.deltaSize());
    }

    // --- helpers ---

    private record SplitObjects(VoltageLevel fictVl, Line half1, Line half2) {
    }

    /**
     * Mint the objects a mid-line split creates: a fictitious voltage level and the two half-lines.
     * They live in a donor network here because at the index level only the object references matter;
     * phase 2 creates them inside the branched network itself via copy-on-write of the endpoint VLs.
     */
    private static SplitObjects mintSplitObjects() {
        Network donor = Network.create("donor", "test");
        Substation s = donor.newSubstation().setId("DS").add();
        VoltageLevel a = s.newVoltageLevel().setId("A").setNominalV(380).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        VoltageLevel fict = s.newVoltageLevel().setId("FICT_VL").setNominalV(380).setFictitious(true)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        VoltageLevel b = s.newVoltageLevel().setId("B").setNominalV(380).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        Bus busA = a.getBusBreakerView().newBus().setId("busA").add();
        Bus busF = fict.getBusBreakerView().newBus().setId("FICT_BUS").add();
        Bus busB = b.getBusBreakerView().newBus().setId("busB").add();
        Line half1 = donor.newLine().setId("L_1").setVoltageLevel1("A").setBus1(busA.getId())
                .setVoltageLevel2("FICT_VL").setBus2(busF.getId())
                .setR(0.5).setX(5).setG1(0).setB1(0).setG2(0).setB2(0).add();
        Line half2 = donor.newLine().setId("L_2").setVoltageLevel1("FICT_VL").setBus1(busF.getId())
                .setVoltageLevel2("B").setBus2(busB.getId())
                .setR(0.5).setX(5).setG1(0).setB1(0).setG2(0).setB2(0).add();
        return new SplitObjects(fict, half1, half2);
    }
}
