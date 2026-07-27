/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

import com.powsybl.loadflow.resultswriter.InMemoryNetworkResultWriter.BranchFlow;
import com.powsybl.loadflow.resultswriter.InMemoryNetworkResultWriter.BusVoltage;
import com.powsybl.loadflow.resultswriter.InMemoryNetworkResultWriter.GeneratorDispatch;
import com.powsybl.loadflow.resultswriter.InMemoryNetworkResultWriter.ThreeWindingsTransformerFlow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author (design proposal)
 */
class InMemoryNetworkResultWriterTest {

    @Test
    void collectsEachDatasetInWriteOrder() {
        InMemoryNetworkResultWriter writer = new InMemoryNetworkResultWriter();
        writer.writeBranchResult("", "", "CONVERGED", "L1", 1.0, 2.0, 3.0, -1.0, -2.0, 3.5, Double.NaN);
        writer.writeBranchResult("c1", "s1", "CONVERGED", "L2", 10.0, 20.0, 30.0, -10.0, -20.0, 35.0, 0.5);
        writer.writeThreeWindingsTransformerResult("c1", "", "CONVERGED", "T3W1", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0);
        writer.writeBusResult("c1", "", "CONVERGED", "B1", 400.0, -1.25);
        writer.writeGeneratorResult("c1", "", "CONVERGED", "G1", 100.0, 102.3);

        List<BranchFlow> branches = writer.getBranchResults();
        assertEquals(2, branches.size());
        assertEquals(new BranchFlow("", "", "CONVERGED", "L1", 1.0, 2.0, 3.0, -1.0, -2.0, 3.5, Double.NaN), branches.get(0));
        assertEquals("L2", branches.get(1).branchId());
        assertEquals("s1", branches.get(1).subStateId());

        assertEquals(List.of(new ThreeWindingsTransformerFlow("c1", "", "CONVERGED", "T3W1", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)),
                writer.getThreeWindingsTransformerResults());
        assertEquals(List.of(new BusVoltage("c1", "", "CONVERGED", "B1", 400.0, -1.25)), writer.getBusResults());
        assertEquals(List.of(new GeneratorDispatch("c1", "", "CONVERGED", "G1", 100.0, 102.3)), writer.getGeneratorResults());
    }

    @Test
    void returnedListsAreUnmodifiable() {
        InMemoryNetworkResultWriter writer = new InMemoryNetworkResultWriter();
        assertThrows(UnsupportedOperationException.class,
                () -> writer.getBranchResults().add(new BranchFlow("", "", "CONVERGED", "L1", 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void factoryMergesRowsAcrossPartitions() {
        InMemoryNetworkResultWriterFactory factory = new InMemoryNetworkResultWriterFactory();
        try (NetworkResultWriter partition0 = factory.create(0)) {
            partition0.writeBranchResult("", "", "CONVERGED", "L1", 1.0, 0, 0, 0, 0, 0, Double.NaN);
        }
        try (NetworkResultWriter partition1 = factory.create(1)) {
            partition1.writeBranchResult("c1", "", "CONVERGED", "L2", 2.0, 0, 0, 0, 0, 0, 0.0);
        }

        List<BranchFlow> merged = factory.getBranchResults();
        assertEquals(2, merged.size());
        assertEquals("L1", merged.get(0).branchId());
        assertEquals("L2", merged.get(1).branchId());
        assertTrue(factory.getBusResults().isEmpty());
        assertTrue(factory.getGeneratorResults().isEmpty());
    }
}
