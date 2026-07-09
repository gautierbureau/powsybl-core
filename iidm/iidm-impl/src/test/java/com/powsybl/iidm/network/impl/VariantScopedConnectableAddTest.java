/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Connectable;
import com.powsybl.iidm.network.EnergySource;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.VoltageLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Structural-variant spike <b>v2.1a</b>: the variant-scoped model is <b>type-agnostic</b>. The same
 * existence + membership machinery that splits a line handles a generator, a load — any connectable —
 * with zero per-type code (see {@link VariantScopedConnectableAdd}). A "what-if we add a generation
 * unit" scenario becomes a cloned variant on the same network; the base variant never sees it.
 *
 * @author Claude
 */
class VariantScopedConnectableAddTest {

    private static NetworkImpl buildBase() {
        // VLA[busA] --L-- VLB[busB], with an existing generator G0 on VLA.
        Network n = Network.create("base", "test");
        Substation sa = n.newSubstation().setId("SA").add();
        VoltageLevel vla = sa.newVoltageLevel().setId("VLA").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vla.getBusBreakerView().newBus().setId("busA").add();
        vla.newGenerator().setId("G0").setConnectableBus("busA").setBus("busA")
                .setMinP(0).setMaxP(100).setTargetP(50).setTargetV(400).setVoltageRegulatorOn(true)
                .setEnergySource(EnergySource.HYDRO).add();
        Substation sb = n.newSubstation().setId("SB").add();
        VoltageLevel vlb = sb.newVoltageLevel().setId("VLB").setNominalV(400).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        vlb.getBusBreakerView().newBus().setId("busB").add();
        n.newLine().setId("L").setVoltageLevel1("VLA").setConnectableBus1("busA").setBus1("busA")
                .setVoltageLevel2("VLB").setConnectableBus2("busB").setBus2("busB")
                .setR(1).setX(10).setG1(0).setB1(0).setG2(0).setB2(0).add();
        return (NetworkImpl) n;
    }

    @Test
    void addingAGeneratorInAVariantLeavesTheBaseUntouched() {
        NetworkImpl n = buildBase();
        VoltageLevelExt vla = (VoltageLevelExt) n.getVoltageLevel("VLA");

        Generator added = VariantScopedConnectableAdd.addInVariant(n, "expansion",
                () -> vla.newGenerator().setId("NEWGEN").setConnectableBus("busA").setBus("busA")
                        .setMinP(0).setMaxP(200).setTargetP(150).setTargetV(400).setVoltageRegulatorOn(true)
                        .setEnergySource(EnergySource.THERMAL).add(),
                vla);
        assertNotNull(added);

        // expansion variant: the new unit exists and is on VLA (enumeration and bus view), next to G0
        n.getVariantManager().setWorkingVariant("expansion");
        assertNotNull(n.getGenerator("NEWGEN"));
        assertEquals(150.0, n.getGenerator("NEWGEN").getTargetP(), 1e-9);
        assertEquals(2, n.getGeneratorCount());
        assertEquals(List.of("G0", "NEWGEN"), generatorIds(vla));
        assertEquals(List.of("G0", "NEWGEN"), busViewGeneratorIds(vla));

        // base variant on the SAME network: the unit does not exist
        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertNull(n.getGenerator("NEWGEN"));
        assertEquals(1, n.getGeneratorCount());
        assertEquals(List.of("G0"), generatorIds(vla));
        assertEquals(List.of("G0"), busViewGeneratorIds(vla));
    }

    @Test
    void theSameMachineryAddsALoad() {
        // Zero per-type code: a load rides exactly the same existence + membership path as a generator.
        NetworkImpl n = buildBase();
        VoltageLevelExt vla = (VoltageLevelExt) n.getVoltageLevel("VLA");

        VariantScopedConnectableAdd.addInVariant(n, "demand",
                () -> vla.newLoad().setId("NEWLOAD").setConnectableBus("busA").setBus("busA")
                        .setP0(20).setQ0(10).add(),
                vla);

        n.getVariantManager().setWorkingVariant("demand");
        assertNotNull(n.getLoad("NEWLOAD"));
        assertEquals(1, vla.getLoadStream().count());

        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertNull(n.getLoad("NEWLOAD"));
        assertEquals(0, vla.getLoadStream().count());
    }

    @Test
    void removingAGeneratorStructurallyInAVariant() {
        // The dual of an add: a "unit decommissioned in this scenario" — hide the object and detach its
        // terminal in one variant. Regular IIDM variants cannot do this (existence is structural).
        NetworkImpl n = buildBase();
        VoltageLevelExt vla = (VoltageLevelExt) n.getVoltageLevel("VLA");
        VariantScopedExistence existence = n.enableVariantScopedExistence();
        VariantScopedMembership membership = n.enableVariantScopedMembership();
        n.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "decommissioned");
        TerminalExt g0Terminal = (TerminalExt) n.getGenerator("G0").getTerminal();

        n.getVariantManager().setWorkingVariant("decommissioned");
        existence.hideInCurrentVariant("G0");
        membership.detachInCurrentVariant(vla, g0Terminal);

        assertNull(n.getGenerator("G0"));
        assertEquals(List.of(), generatorIds(vla));
        assertEquals(List.of(), busViewGeneratorIds(vla));

        // base variant keeps the unit
        n.getVariantManager().setWorkingVariant(VariantManagerConstants.INITIAL_VARIANT_ID);
        assertNotNull(n.getGenerator("G0"));
        assertEquals(List.of("G0"), generatorIds(vla));
    }

    private static List<String> generatorIds(VoltageLevel vl) {
        return vl.getConnectableStream(Generator.class).map(Generator::getId).sorted().toList();
    }

    private static List<String> busViewGeneratorIds(VoltageLevel vl) {
        return StreamSupport.stream(vl.getBusView().getBuses().spliterator(), false)
                .flatMap(Bus::getConnectedTerminalStream)
                .map(t -> t.getConnectable())
                .filter(Generator.class::isInstance)
                .map(Connectable::getId)
                .sorted()
                .toList();
    }
}
