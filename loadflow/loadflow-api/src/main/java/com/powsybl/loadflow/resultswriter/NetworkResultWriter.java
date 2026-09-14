/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

/**
 * A streaming sink for network computation results (branch and three-winding transformer flows, bus voltages,
 * generator dispatch).
 *
 * <p>This is a provider-agnostic seam shared by any analysis that solves the same network many times and produces a
 * large, wide result set: security analysis (one state per contingency / operator strategy) and time-series load flow
 * (one state per time step) are the two current consumers. Producers call the {@code write*Result} methods once per
 * element, for each state, as soon as the corresponding results are computed. This lets the results be persisted
 * without keeping the whole set in memory, which does not scale for large networks with many states.
 *
 * <p>Every row is identified by two generic key columns whose meaning is defined by the producer:
 * <ul>
 *     <li>{@code stateId} — the primary state key (e.g. a contingency id for security analysis, or a time-step
 *         timestamp for time-series load flow); empty for the reference/base state;</li>
 *     <li>{@code subStateId} — an optional secondary key (e.g. an operator-strategy id for security analysis); empty
 *         when not applicable.</li>
 * </ul>
 *
 * <p>The methods are intentionally primitive (no per-row object to allocate) so that producers can feed a columnar
 * output (e.g. Parquet) directly from the solved network state — a "vectorized" write path. Each {@code write*Result}
 * family maps to its own dataset (schema); a producer only emits the datasets it cares about (security analysis, for
 * instance, only calls {@link #writeBranchResult}).
 *
 * <p>A single writer instance is <b>not</b> required to be thread-safe: producers running states on several threads
 * obtain one writer per thread from a {@link NetworkResultWriterFactory} (e.g. one part file per partition), so each
 * thread writes to its own writer without locking.
 *
 * @author (design proposal)
 */
public interface NetworkResultWriter extends AutoCloseable {

    /**
     * A writer that does nothing. This is the default so that streaming is fully opt-in and the behaviour of producers
     * that do not support it, or of runs that do not request it, is unchanged.
     */
    NetworkResultWriter NO_OP = new NetworkResultWriter() {
        // all methods are no-op defaults
    };

    /**
     * Write one branch flow row.
     *
     * @param stateId       the primary state key (empty string for the reference/base state)
     * @param subStateId    the secondary state key (empty string when not applicable)
     * @param status        the computation status of the state the flow belongs to
     * @param branchId      the id of the branch
     * @param p1            active power at side 1 (MW)
     * @param q1            reactive power at side 1 (MVar)
     * @param i1            current at side 1 (A)
     * @param p2            active power at side 2 (MW)
     * @param q2            reactive power at side 2 (MVar)
     * @param i2            current at side 2 (A)
     * @param flowTransfer  flow transfer ratio (NaN when not applicable, e.g. base case or time-series step)
     */
    default void writeBranchResult(String stateId, String subStateId, String status, String branchId,
                                   double p1, double q1, double i1,
                                   double p2, double q2, double i2, double flowTransfer) {
        // no-op by default
    }

    /**
     * Write one three-winding transformer flow row (one active/reactive power and current per leg).
     *
     * @param stateId                     the primary state key (empty string for the reference/base state)
     * @param subStateId                  the secondary state key (empty string when not applicable)
     * @param status                      the computation status of the state the flow belongs to
     * @param threeWindingsTransformerId  the id of the three-winding transformer
     * @param p1                          active power at leg 1 (MW)
     * @param q1                          reactive power at leg 1 (MVar)
     * @param i1                          current at leg 1 (A)
     * @param p2                          active power at leg 2 (MW)
     * @param q2                          reactive power at leg 2 (MVar)
     * @param i2                          current at leg 2 (A)
     * @param p3                          active power at leg 3 (MW)
     * @param q3                          reactive power at leg 3 (MVar)
     * @param i3                          current at leg 3 (A)
     */
    default void writeThreeWindingsTransformerResult(String stateId, String subStateId, String status,
                                                     String threeWindingsTransformerId,
                                                     double p1, double q1, double i1,
                                                     double p2, double q2, double i2,
                                                     double p3, double q3, double i3) {
        // no-op by default
    }

    /**
     * Write one bus voltage row.
     *
     * @param stateId    the primary state key (empty string for the reference/base state)
     * @param subStateId the secondary state key (empty string when not applicable)
     * @param status     the computation status of the state the voltage belongs to
     * @param busId      the id of the bus
     * @param v          voltage magnitude (kV)
     * @param angle      voltage angle (degrees)
     */
    default void writeBusResult(String stateId, String subStateId, String status, String busId,
                                double v, double angle) {
        // no-op by default
    }

    /**
     * Write one generator dispatch row.
     *
     * @param stateId     the primary state key (empty string for the reference/base state)
     * @param subStateId  the secondary state key (empty string when not applicable)
     * @param status      the computation status of the state the dispatch belongs to
     * @param generatorId the id of the generator
     * @param targetP     the requested active power target (MW), i.e. the setpoint before slack distribution
     * @param p           the actual active power (MW) after slack distribution / balancing
     */
    default void writeGeneratorResult(String stateId, String subStateId, String status, String generatorId,
                                      double targetP, double p) {
        // no-op by default
    }

    /**
     * Flush and release any underlying resource (file, stream...). Called once when the partition this writer serves is
     * finished.
     */
    @Override
    default void close() {
        // nothing to close by default
    }
}
