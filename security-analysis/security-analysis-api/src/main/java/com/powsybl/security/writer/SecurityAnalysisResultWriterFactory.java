/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.security.writer;

/**
 * Builds one {@link SecurityAnalysisResultWriter} per contingency partition.
 *
 * <p>Providers that run contingencies on several threads partition the contingencies and process each partition on its
 * own thread. By giving each partition its own writer (for instance one {@code part-<i>.parquet} file), streaming is
 * lock-free: no two threads ever write to the same writer. Only partition {@code 0} is expected to write the base-case
 * rows, to avoid duplicating them across partitions.
 *
 * @author (design proposal)
 */
@FunctionalInterface
public interface SecurityAnalysisResultWriterFactory {

    /**
     * A factory that always returns the no-op writer, i.e. streaming disabled. This is the default.
     */
    SecurityAnalysisResultWriterFactory NO_OP = partitionIndex -> SecurityAnalysisResultWriter.NO_OP;

    /**
     * Create the writer for one contingency partition.
     *
     * @param partitionIndex the 0-based partition index (always 0 in the single-threaded case)
     * @return the writer that partition will use; must not be null
     */
    SecurityAnalysisResultWriter create(int partitionIndex);
}
