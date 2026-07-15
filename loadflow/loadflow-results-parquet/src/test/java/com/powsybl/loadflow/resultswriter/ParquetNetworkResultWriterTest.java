/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

import blue.strategic.parquet.Hydrator;
import blue.strategic.parquet.HydratorSupplier;
import blue.strategic.parquet.ParquetReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author (design proposal)
 */
class ParquetNetworkResultWriterTest {

    private static List<Map<String, Object>> readBack(File file) throws IOException {
        HydratorSupplier<Map<String, Object>, Map<String, Object>> supplier = HydratorSupplier.constantly(new Hydrator<>() {
            @Override
            public Map<String, Object> start() {
                return new HashMap<>();
            }

            @Override
            public Map<String, Object> add(Map<String, Object> target, String heading, Object value) {
                target.put(heading, value);
                return target;
            }

            @Override
            public Map<String, Object> finish(Map<String, Object> target) {
                return target;
            }
        });
        try (Stream<Map<String, Object>> stream = ParquetReader.streamContent(file, supplier)) {
            return stream.toList();
        }
    }

    private static Map<String, Map<String, Object>> byId(List<Map<String, Object>> rows, String idColumn) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byId.put(String.valueOf(row.get(idColumn)), row);
        }
        return byId;
    }

    @Test
    void writeAndReadBackBranches(@TempDir Path dir) throws IOException {
        try (ParquetNetworkResultWriter writer = new ParquetNetworkResultWriter(
                dataset -> dir.resolve(dataset + ".parquet").toFile())) {
            writer.writeBranchResult("", "", "CONVERGED", "L1", 1.0, 2.0, 3.0, -1.0, -2.0, 3.5, Double.NaN);
            writer.writeBranchResult("2025-01-01T00:00:00Z", "", "CONVERGED", "L2", 10.0, 20.0, 30.0, -10.0, -20.0, 35.0, 0.5);
        }

        List<Map<String, Object>> rows = readBack(dir.resolve(CsvNetworkResultWriter.BRANCHES + ".parquet").toFile());
        assertEquals(2, rows.size());
        Map<String, Map<String, Object>> byBranch = byId(rows, "branchId");

        Map<String, Object> l1 = byBranch.get("L1");
        assertEquals("", String.valueOf(l1.get("stateId")));
        assertEquals("", String.valueOf(l1.get("subStateId")));
        assertEquals("CONVERGED", String.valueOf(l1.get("status")));
        assertEquals(1.0, (double) l1.get("p1"));
        assertEquals(3.5, (double) l1.get("i2"));
        assertTrue(Double.isNaN((double) l1.get("flowTransfer")));

        Map<String, Object> l2 = byBranch.get("L2");
        assertEquals("2025-01-01T00:00:00Z", String.valueOf(l2.get("stateId")));
        assertEquals(10.0, (double) l2.get("p1"));
        assertEquals(0.5, (double) l2.get("flowTransfer"));
    }

    @Test
    void writeAndReadBackBusesAndGenerators(@TempDir Path dir) throws IOException {
        try (ParquetNetworkResultWriter writer = new ParquetNetworkResultWriter(
                dataset -> dir.resolve(dataset + ".parquet").toFile())) {
            writer.writeBusResult("", "", "CONVERGED", "B1", 400.0, 0.0);
            writer.writeBusResult("2025-01-01T00:00:00Z", "", "CONVERGED", "B2", 398.5, -1.25);
            writer.writeGeneratorResult("2025-01-01T00:00:00Z", "", "CONVERGED", "G1", 100.0, 102.3);
        }

        Map<String, Map<String, Object>> buses = byId(readBack(dir.resolve(CsvNetworkResultWriter.BUSES + ".parquet").toFile()), "busId");
        assertEquals(2, buses.size());
        assertEquals(400.0, (double) buses.get("B1").get("v"));
        assertEquals(-1.25, (double) buses.get("B2").get("angle"));

        List<Map<String, Object>> generatorRows = readBack(dir.resolve(CsvNetworkResultWriter.GENERATORS + ".parquet").toFile());
        assertEquals(1, generatorRows.size());
        Map<String, Object> g1 = generatorRows.get(0);
        assertEquals("G1", String.valueOf(g1.get("generatorId")));
        assertEquals(100.0, (double) g1.get("targetP"));
        assertEquals(102.3, (double) g1.get("p"));
    }

    @Test
    void datasetFilesAreCreatedLazily(@TempDir Path dir) throws IOException {
        // a producer that only writes branch rows must not create bus/generator files
        try (ParquetNetworkResultWriter writer = new ParquetNetworkResultWriter(
                dataset -> dir.resolve(dataset + ".parquet").toFile())) {
            writer.writeBranchResult("", "", "CONVERGED", "L1", 1.0, 2.0, 3.0, -1.0, -2.0, 3.5, Double.NaN);
        }
        assertTrue(Files.exists(dir.resolve(CsvNetworkResultWriter.BRANCHES + ".parquet")));
        assertFalse(Files.exists(dir.resolve(CsvNetworkResultWriter.BUSES + ".parquet")));
        assertFalse(Files.exists(dir.resolve(CsvNetworkResultWriter.GENERATORS + ".parquet")));
    }
}
