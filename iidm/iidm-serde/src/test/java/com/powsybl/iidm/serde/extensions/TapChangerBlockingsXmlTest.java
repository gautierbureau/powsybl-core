/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.serde.extensions;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.ControlVoltageLevel;
import com.powsybl.iidm.network.extensions.MeasurementPoint;
import com.powsybl.iidm.network.extensions.TapChangerBlocking;
import com.powsybl.iidm.network.extensions.TapChangerBlockings;
import com.powsybl.iidm.network.extensions.TapChangerBlockingsAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.serde.NetworkSerDe;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Round trips the tap changer blockings, checking the buses and busbar sections a point is measured
 * at are kept apart, a bus carrying its voltage level, through the extension and its serialization.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class TapChangerBlockingsXmlTest {

    private static Network networkWithTcb() {
        Network network = EurostagTutorialExample1Factory.create();
        network.newExtension(TapChangerBlockingsAdder.class)
                .newTapChangerBlocking()
                    .withName("tcb1")
                    .newMeasurementPoint()
                        .withId("mp1")
                        .withBuses(List.of(new MeasurementPoint.BusRef("VLHV1", "NHV1"),
                                new MeasurementPoint.BusRef("VLHV2", "NHV2")))
                        .withBusbarSectionIds(List.of("BBS1"))
                        .add()
                    .newMeasurementPoint()
                        .withId("mp2")
                        .withBusbarSectionIds(List.of("BBS2", "BBS3"))
                        .add()
                    .newControlVoltageLevel()
                        .withId("VLHV1")
                        .add()
                    .newControlVoltageLevel()
                        .withId("VLHV2")
                        .add()
                    .add()
                .add();
        return network;
    }

    private static void assertTcb(Network network) {
        TapChangerBlockings tcbs = network.getExtension(TapChangerBlockings.class);
        assertNotNull(tcbs);
        TapChangerBlocking tcb = tcbs.getTapChangerBlocking("tcb1").orElseThrow();

        MeasurementPoint mp1 = tcb.getMeasurementPoint("mp1").orElseThrow();
        // the buses keep their voltage level, told apart from the busbar sections
        assertEquals(List.of(new MeasurementPoint.BusRef("VLHV1", "NHV1"),
                new MeasurementPoint.BusRef("VLHV2", "NHV2")), mp1.getBuses());
        assertEquals(List.of("BBS1"), mp1.getBusbarSectionIds());

        MeasurementPoint mp2 = tcb.getMeasurementPoint("mp2").orElseThrow();
        assertEquals(List.of(), mp2.getBuses());
        assertEquals(List.of("BBS2", "BBS3"), mp2.getBusbarSectionIds());

        ControlVoltageLevel cvl2 = tcb.getControlVoltageLevel("VLHV2").orElseThrow();
        assertEquals("VLHV2", cvl2.getId());
        assertEquals("VLHV1", tcb.getControlVoltageLevel("VLHV1").orElseThrow().getId());
    }

    @Test
    void modelHoldsBusesApartFromBusbarSections() {
        assertTcb(networkWithTcb());
    }

    @Test
    void roundTripsThroughXml() {
        Network network = networkWithTcb();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NetworkSerDe.write(network, out);
        Network read = NetworkSerDe.read(new ByteArrayInputStream(out.toByteArray()));
        assertTcb(read);
    }
}
