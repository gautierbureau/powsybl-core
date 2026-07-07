/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.security.writer;

import com.powsybl.security.results.PostContingencyResult;
import com.powsybl.security.results.PreContingencyResult;

/**
 * A streaming sink for security analysis results.
 *
 * <p>Security analysis providers that support streaming call this writer incrementally, once for the
 * pre-contingency (base case) state and once for each post-contingency state, as soon as the corresponding
 * results are computed. This allows the monitored quantities (typically all branch flows) to be persisted
 * without keeping the whole result set in memory, which does not scale for large networks with many
 * contingencies.
 *
 * <p>Implementations are not required to be thread-safe. When a provider runs contingencies on several
 * threads, it is expected to use one writer instance per thread (for instance one part file per partition).
 *
 * @author (design proposal)
 */
public interface SecurityAnalysisResultWriter extends AutoCloseable {

    /**
     * A writer that does nothing. This is the default so that streaming is fully opt-in and the behaviour of
     * providers that do not support it, or of runs that do not request it, is unchanged.
     */
    SecurityAnalysisResultWriter NO_OP = new SecurityAnalysisResultWriter() {
        @Override
        public void writePreContingencyResult(PreContingencyResult preContingencyResult) {
            // no-op
        }

        @Override
        public void writePostContingencyResult(PostContingencyResult postContingencyResult) {
            // no-op
        }
    };

    /**
     * Write the pre-contingency (base case) result. Called at most once, before any post-contingency result.
     */
    void writePreContingencyResult(PreContingencyResult preContingencyResult);

    /**
     * Write one post-contingency result. Called once per contingency, in no guaranteed order.
     */
    void writePostContingencyResult(PostContingencyResult postContingencyResult);

    /**
     * Flush and release any underlying resource (file, stream...). Called once at the end of the analysis.
     */
    @Override
    default void close() {
        // nothing to close by default
    }
}
