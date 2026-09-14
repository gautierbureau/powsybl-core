/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link NetworkResultWriter} that keeps every row in memory instead of persisting it, exposing the collected rows
 * as immutable lists. This is the in-memory reference implementation of the seam: consumers that want the whole result
 * set in memory (small networks, tests, or an API that returns results directly) use this rather than the CSV / Parquet
 * file backends.
 *
 * <p>Rows are stored flat, in write order, one list per dataset. The generic {@code stateId} / {@code subStateId} keys
 * are kept verbatim on each row; a consumer that needs a per-state view groups the rows by those keys itself (their
 * meaning is defined by the producer, so this writer does not interpret them).
 *
 * <p>A single instance is <b>not</b> thread-safe, matching the seam contract: multi-threaded producers obtain one
 * instance per partition from {@link InMemoryNetworkResultWriterFactory}, which then merges the per-partition rows.
 *
 * @author (design proposal)
 */
public class InMemoryNetworkResultWriter implements NetworkResultWriter {

    /**
     * One branch flow row, mirroring the arguments of {@link #writeBranchResult}.
     */
    public record BranchFlow(String stateId, String subStateId, String status, String branchId,
                             double p1, double q1, double i1,
                             double p2, double q2, double i2, double flowTransfer) {
    }

    /**
     * One three-winding transformer flow row, mirroring the arguments of
     * {@link #writeThreeWindingsTransformerResult}.
     */
    public record ThreeWindingsTransformerFlow(String stateId, String subStateId, String status,
                                               String threeWindingsTransformerId,
                                               double p1, double q1, double i1,
                                               double p2, double q2, double i2,
                                               double p3, double q3, double i3) {
    }

    /**
     * One bus voltage row, mirroring the arguments of {@link #writeBusResult}.
     */
    public record BusVoltage(String stateId, String subStateId, String status, String busId,
                             double v, double angle) {
    }

    /**
     * One generator dispatch row, mirroring the arguments of {@link #writeGeneratorResult}.
     */
    public record GeneratorDispatch(String stateId, String subStateId, String status, String generatorId,
                                    double targetP, double p) {
    }

    private final List<BranchFlow> branchResults = new ArrayList<>();
    private final List<ThreeWindingsTransformerFlow> threeWindingsTransformerResults = new ArrayList<>();
    private final List<BusVoltage> busResults = new ArrayList<>();
    private final List<GeneratorDispatch> generatorResults = new ArrayList<>();

    @Override
    public void writeBranchResult(String stateId, String subStateId, String status, String branchId,
                                  double p1, double q1, double i1,
                                  double p2, double q2, double i2, double flowTransfer) {
        branchResults.add(new BranchFlow(stateId, subStateId, status, branchId, p1, q1, i1, p2, q2, i2, flowTransfer));
    }

    @Override
    public void writeThreeWindingsTransformerResult(String stateId, String subStateId, String status,
                                                    String threeWindingsTransformerId,
                                                    double p1, double q1, double i1,
                                                    double p2, double q2, double i2,
                                                    double p3, double q3, double i3) {
        threeWindingsTransformerResults.add(new ThreeWindingsTransformerFlow(stateId, subStateId, status,
                threeWindingsTransformerId, p1, q1, i1, p2, q2, i2, p3, q3, i3));
    }

    @Override
    public void writeBusResult(String stateId, String subStateId, String status, String busId,
                               double v, double angle) {
        busResults.add(new BusVoltage(stateId, subStateId, status, busId, v, angle));
    }

    @Override
    public void writeGeneratorResult(String stateId, String subStateId, String status, String generatorId,
                                     double targetP, double p) {
        generatorResults.add(new GeneratorDispatch(stateId, subStateId, status, generatorId, targetP, p));
    }

    /**
     * The branch flow rows collected so far, in write order.
     */
    public List<BranchFlow> getBranchResults() {
        return Collections.unmodifiableList(branchResults);
    }

    /**
     * The three-winding transformer flow rows collected so far, in write order.
     */
    public List<ThreeWindingsTransformerFlow> getThreeWindingsTransformerResults() {
        return Collections.unmodifiableList(threeWindingsTransformerResults);
    }

    /**
     * The bus voltage rows collected so far, in write order.
     */
    public List<BusVoltage> getBusResults() {
        return Collections.unmodifiableList(busResults);
    }

    /**
     * The generator dispatch rows collected so far, in write order.
     */
    public List<GeneratorDispatch> getGeneratorResults() {
        return Collections.unmodifiableList(generatorResults);
    }
}
