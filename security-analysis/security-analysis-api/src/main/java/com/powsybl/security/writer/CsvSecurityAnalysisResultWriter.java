/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.security.writer;

import com.powsybl.commons.io.table.Column;
import com.powsybl.commons.io.table.CsvTableFormatter;
import com.powsybl.commons.io.table.TableFormatter;
import com.powsybl.commons.io.table.TableFormatterConfig;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.PostContingencyResult;
import com.powsybl.security.results.PreContingencyResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Objects;

/**
 * A {@link SecurityAnalysisResultWriter} that streams monitored branch flows to a CSV output.
 *
 * <p>One row is written per (contingency, branch): {@code contingencyId; status; branchId; p1; q1; i1; p2; q2; i2; flowTransfer}.
 * The pre-contingency (base case) state is written with an empty contingency id ({@link #PRE_CONTINGENCY_ID}).
 *
 * <p>CSV is intentionally the first, dependency-free output format. A columnar format such as Parquet is expected
 * to be provided later as a separate implementation of {@link SecurityAnalysisResultWriter}.
 *
 * @author (design proposal)
 */
public class CsvSecurityAnalysisResultWriter implements SecurityAnalysisResultWriter {

    /** Contingency id used for the pre-contingency (base case) rows. */
    public static final String PRE_CONTINGENCY_ID = "";

    private final TableFormatter formatter;

    public CsvSecurityAnalysisResultWriter(Writer writer) {
        // Locale.US so the decimal separator is '.' and there is no digit grouping, and ';' as field separator.
        this(writer, new TableFormatterConfig(Locale.US, ';', "", true, false));
    }

    public CsvSecurityAnalysisResultWriter(Writer writer, TableFormatterConfig config) {
        Objects.requireNonNull(writer);
        Objects.requireNonNull(config);
        this.formatter = new CsvTableFormatter(writer, "", config,
                new Column("contingencyId"),
                new Column("status"),
                new Column("branchId"),
                new Column("p1"),
                new Column("q1"),
                new Column("i1"),
                new Column("p2"),
                new Column("q2"),
                new Column("i2"),
                new Column("flowTransfer"));
    }

    @Override
    public void writePreContingencyResult(PreContingencyResult preContingencyResult) {
        Objects.requireNonNull(preContingencyResult);
        String status = preContingencyResult.getStatus().name();
        for (BranchResult branchResult : preContingencyResult.getNetworkResult().getBranchResults()) {
            writeBranchRow(PRE_CONTINGENCY_ID, status, branchResult);
        }
    }

    @Override
    public void writePostContingencyResult(PostContingencyResult postContingencyResult) {
        Objects.requireNonNull(postContingencyResult);
        String contingencyId = postContingencyResult.getContingency().getId();
        String status = postContingencyResult.getStatus().name();
        for (BranchResult branchResult : postContingencyResult.getNetworkResult().getBranchResults()) {
            writeBranchRow(contingencyId, status, branchResult);
        }
    }

    private void writeBranchRow(String contingencyId, String status, BranchResult branchResult) {
        try {
            formatter.writeCell(contingencyId)
                    .writeCell(status)
                    .writeCell(branchResult.getBranchId())
                    .writeCell(branchResult.getP1())
                    .writeCell(branchResult.getQ1())
                    .writeCell(branchResult.getI1())
                    .writeCell(branchResult.getP2())
                    .writeCell(branchResult.getQ2())
                    .writeCell(branchResult.getI2())
                    .writeCell(branchResult.getFlowTransfer());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            formatter.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
