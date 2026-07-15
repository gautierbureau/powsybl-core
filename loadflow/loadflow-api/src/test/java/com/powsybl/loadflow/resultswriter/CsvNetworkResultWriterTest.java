/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author (design proposal)
 */
class CsvNetworkResultWriterTest {

    @Test
    void writesEachDatasetWithGenericStateKeys() {
        Map<String, StringWriter> writers = new HashMap<>();
        try (CsvNetworkResultWriter writer = new CsvNetworkResultWriter(datasetWriterProvider(writers))) {
            writer.writeBranchResult("", "", "CONVERGED", "L1", 1.0, 2.0, 3.0, -1.0, -2.0, 3.5, Double.NaN);
            writer.writeBranchResult("2025-01-01T00:00:00Z", "", "CONVERGED", "L2", 10.0, 20.0, 30.0, -10.0, -20.0, 35.0, 0.5);
            writer.writeBusResult("2025-01-01T00:00:00Z", "", "CONVERGED", "B1", 400.0, -1.25);
            writer.writeGeneratorResult("2025-01-01T00:00:00Z", "", "CONVERGED", "G1", 100.0, 102.3);
        }

        String branches = writers.get(CsvNetworkResultWriter.BRANCHES).toString();
        assertTrue(branches.startsWith("stateId;subStateId;status;branchId;p1;q1;i1;p2;q2;i2;flowTransfer"),
                () -> "unexpected branch header: " + branches);
        assertTrue(branches.contains("2025-01-01T00:00:00Z;;CONVERGED;L2;"), () -> branches);

        String buses = writers.get(CsvNetworkResultWriter.BUSES).toString();
        assertTrue(buses.startsWith("stateId;subStateId;status;busId;v;angle"), () -> buses);

        String generators = writers.get(CsvNetworkResultWriter.GENERATORS).toString();
        assertTrue(generators.startsWith("stateId;subStateId;status;generatorId;targetP;p"), () -> generators);
    }

    @Test
    void datasetsAreOpenedLazily() {
        Map<String, StringWriter> writers = new HashMap<>();
        try (CsvNetworkResultWriter writer = new CsvNetworkResultWriter(datasetWriterProvider(writers))) {
            writer.writeBranchResult("", "", "CONVERGED", "L1", 1.0, 2.0, 3.0, -1.0, -2.0, 3.5, Double.NaN);
        }
        assertTrue(writers.containsKey(CsvNetworkResultWriter.BRANCHES));
        assertFalse(writers.containsKey(CsvNetworkResultWriter.BUSES));
        assertFalse(writers.containsKey(CsvNetworkResultWriter.GENERATORS));
    }

    @Test
    void noOpWriterDoesNothing() {
        // exercises the default no-op methods; must not throw
        try (NetworkResultWriter writer = NetworkResultWriter.NO_OP) {
            writer.writeBranchResult("", "", "CONVERGED", "L1", 1.0, 2.0, 3.0, -1.0, -2.0, 3.5, Double.NaN);
            writer.writeBusResult("", "", "CONVERGED", "B1", 400.0, 0.0);
            writer.writeGeneratorResult("", "", "CONVERGED", "G1", 100.0, 100.0);
        }
        assertEquals(NetworkResultWriter.NO_OP, NetworkResultWriterFactory.NO_OP.create(0));
    }

    private static java.util.function.Function<String, Writer> datasetWriterProvider(Map<String, StringWriter> writers) {
        return dataset -> writers.computeIfAbsent(dataset, k -> new StringWriter());
    }
}
