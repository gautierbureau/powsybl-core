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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link NetworkResultWriterFactory} that hands each partition an {@link InMemoryNetworkResultWriter} and keeps a
 * reference to it, so that once every partition is done the rows of all partitions can be read back merged.
 *
 * <p>The per-partition writers are not thread-safe (one per thread, per the seam contract), but the factory itself is:
 * {@link #create(int)} can be called concurrently from the partition threads, and the merged getters can be read once
 * the run has finished. Merged rows are returned in ascending partition-creation order.
 *
 * @author (design proposal)
 */
public class InMemoryNetworkResultWriterFactory implements NetworkResultWriterFactory {

    private final List<InMemoryNetworkResultWriter> writers = new CopyOnWriteArrayList<>();

    @Override
    public NetworkResultWriter create(int partitionIndex) {
        InMemoryNetworkResultWriter writer = new InMemoryNetworkResultWriter();
        writers.add(writer);
        return writer;
    }

    /**
     * The branch flow rows of all partitions, concatenated.
     */
    public List<BranchFlow> getBranchResults() {
        List<BranchFlow> merged = new ArrayList<>();
        for (InMemoryNetworkResultWriter writer : writers) {
            merged.addAll(writer.getBranchResults());
        }
        return Collections.unmodifiableList(merged);
    }

    /**
     * The three-winding transformer flow rows of all partitions, concatenated.
     */
    public List<ThreeWindingsTransformerFlow> getThreeWindingsTransformerResults() {
        List<ThreeWindingsTransformerFlow> merged = new ArrayList<>();
        for (InMemoryNetworkResultWriter writer : writers) {
            merged.addAll(writer.getThreeWindingsTransformerResults());
        }
        return Collections.unmodifiableList(merged);
    }

    /**
     * The bus voltage rows of all partitions, concatenated.
     */
    public List<BusVoltage> getBusResults() {
        List<BusVoltage> merged = new ArrayList<>();
        for (InMemoryNetworkResultWriter writer : writers) {
            merged.addAll(writer.getBusResults());
        }
        return Collections.unmodifiableList(merged);
    }

    /**
     * The generator dispatch rows of all partitions, concatenated.
     */
    public List<GeneratorDispatch> getGeneratorResults() {
        List<GeneratorDispatch> merged = new ArrayList<>();
        for (InMemoryNetworkResultWriter writer : writers) {
            merged.addAll(writer.getGeneratorResults());
        }
        return Collections.unmodifiableList(merged);
    }
}
