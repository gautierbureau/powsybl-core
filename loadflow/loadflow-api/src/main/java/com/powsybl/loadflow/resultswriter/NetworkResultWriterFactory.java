/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

/**
 * Builds one {@link NetworkResultWriter} per computation partition.
 *
 * <p>Producers that solve states (contingencies, time steps...) on several threads partition the work and process each
 * partition on its own thread. By giving each partition its own writer (for instance one {@code part-<i>} file per
 * dataset), streaming is lock-free: no two threads ever write to the same writer. By convention only partition
 * {@code 0} writes the reference/base-state rows, to avoid duplicating them across partitions.
 *
 * @author (design proposal)
 */
@FunctionalInterface
public interface NetworkResultWriterFactory {

    /**
     * A factory that always returns the no-op writer, i.e. streaming disabled. This is the default.
     */
    NetworkResultWriterFactory NO_OP = partitionIndex -> NetworkResultWriter.NO_OP;

    /**
     * Create the writer for one partition.
     *
     * @param partitionIndex the 0-based partition index (always 0 in the single-threaded case)
     * @return the writer that partition will use; must not be null
     */
    NetworkResultWriter create(int partitionIndex);
}
