/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.security.writer;

/**
 * A streaming sink for security analysis branch flows.
 *
 * <p>Security analysis providers that support streaming call {@link #writeBranchResult} once per monitored branch, for
 * the base case and for each post-contingency state, as soon as the corresponding results are computed. This lets the
 * flows be persisted without keeping the whole result set in memory, which does not scale for large networks with many
 * contingencies.
 *
 * <p>The method is intentionally primitive (no per-row object to allocate) so that providers can feed a columnar output
 * (e.g. Parquet) directly from the solved network state — a "vectorized" write path.
 *
 * <p>A single writer instance is <b>not</b> required to be thread-safe: providers running contingencies on several
 * threads obtain one writer per thread from a {@link SecurityAnalysisResultWriterFactory} (e.g. one part file per
 * partition), so each thread writes to its own writer without locking.
 *
 * @author (design proposal)
 */
public interface SecurityAnalysisResultWriter extends AutoCloseable {

    /**
     * A writer that does nothing. This is the default so that streaming is fully opt-in and the behaviour of providers
     * that do not support it, or of runs that do not request it, is unchanged.
     */
    SecurityAnalysisResultWriter NO_OP = (contingencyId, operatorStrategyId, status, branchId, p1, q1, i1, p2, q2, i2, flowTransfer) -> {
        // no-op
    };

    /**
     * Write one monitored branch flow row.
     *
     * <p>The three id columns together identify the state the flow belongs to: base case (both {@code contingencyId} and
     * {@code operatorStrategyId} empty), post-contingency ({@code contingencyId} set, {@code operatorStrategyId} empty),
     * or operator-strategy ({@code contingencyId} and {@code operatorStrategyId} both set).
     *
     * @param contingencyId     the id of the contingency, or an empty string for the base (pre-contingency) case
     * @param operatorStrategyId the id of the operator strategy, or an empty string when not an operator-strategy state
     * @param status            the computation status of the state the flow belongs to
     * @param branchId          the id of the branch
     * @param p1                active power at side 1 (MW)
     * @param q1                reactive power at side 1 (MVar)
     * @param i1                current at side 1 (A)
     * @param p2                active power at side 2 (MW)
     * @param q2                reactive power at side 2 (MVar)
     * @param i2                current at side 2 (A)
     * @param flowTransfer      flow transfer ratio (NaN for the base case)
     */
    void writeBranchResult(String contingencyId, String operatorStrategyId, String status, String branchId,
                           double p1, double q1, double i1,
                           double p2, double q2, double i2, double flowTransfer);

    /**
     * Flush and release any underlying resource (file, stream...). Called once when the partition this writer serves is
     * finished.
     */
    @Override
    default void close() {
        // nothing to close by default
    }
}
