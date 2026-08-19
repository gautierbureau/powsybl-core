/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.security.writer.parquet;

import blue.strategic.parquet.Hydrator;
import blue.strategic.parquet.HydratorSupplier;
import blue.strategic.parquet.ParquetReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author (design proposal)
 */
class ParquetSecurityAnalysisResultWriterTest {

    @Test
    void writeAndReadBack(@TempDir Path dir) throws IOException {
        File file = dir.resolve("part-0.parquet").toFile();

        try (ParquetSecurityAnalysisResultWriter writer = new ParquetSecurityAnalysisResultWriter(file)) {
            writer.writeBranchResult("", "", "CONVERGED", "L1", 1.0, 2.0, 3.0, -1.0, -2.0, 3.5, Double.NaN);
            writer.writeBranchResult("cont1", "strategy1", "CONVERGED", "L2", 10.0, 20.0, 30.0, -10.0, -20.0, 35.0, 0.5);
        }

        // read each row back into a column-name -> value map
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

        List<Map<String, Object>> rows;
        try (Stream<Map<String, Object>> stream = ParquetReader.streamContent(file, supplier)) {
            rows = stream.toList();
        }

        assertEquals(2, rows.size());

        Map<String, Map<String, Object>> byBranch = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byBranch.put(String.valueOf(row.get("branchId")), row);
        }

        Map<String, Object> l1 = byBranch.get("L1");
        assertEquals("", String.valueOf(l1.get("contingencyId")));
        assertEquals("", String.valueOf(l1.get("operatorStrategyId")));
        assertEquals("CONVERGED", String.valueOf(l1.get("status")));
        assertEquals(1.0, (double) l1.get("p1"));
        assertEquals(3.5, (double) l1.get("i2"));
        assertTrue(Double.isNaN((double) l1.get("flowTransfer")));

        Map<String, Object> l2 = byBranch.get("L2");
        assertEquals("cont1", String.valueOf(l2.get("contingencyId")));
        assertEquals("strategy1", String.valueOf(l2.get("operatorStrategyId")));
        assertEquals(10.0, (double) l2.get("p1"));
        assertEquals(0.5, (double) l2.get("flowTransfer"));
    }
}
