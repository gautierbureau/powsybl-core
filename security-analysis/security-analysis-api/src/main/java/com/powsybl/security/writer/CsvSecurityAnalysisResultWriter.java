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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Objects;

/**
 * A {@link SecurityAnalysisResultWriter} that streams monitored branch flows to a CSV output.
 *
 * <p>One row is written per (contingency, branch): {@code contingencyId; status; branchId; p1; q1; i1; p2; q2; i2; flowTransfer}.
 * The base (pre-contingency) case is written with an empty contingency id.
 *
 * <p>CSV is intentionally the first, dependency-free output format. A columnar format such as Parquet is provided by a
 * separate implementation of {@link SecurityAnalysisResultWriter}.
 *
 * @author (design proposal)
 */
public class CsvSecurityAnalysisResultWriter implements SecurityAnalysisResultWriter {

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
                new Column("operatorStrategyId"),
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
    public void writeBranchResult(String contingencyId, String operatorStrategyId, String status, String branchId,
                                  double p1, double q1, double i1,
                                  double p2, double q2, double i2, double flowTransfer) {
        try {
            formatter.writeCell(contingencyId)
                    .writeCell(operatorStrategyId)
                    .writeCell(status)
                    .writeCell(branchId)
                    .writeCell(p1)
                    .writeCell(q1)
                    .writeCell(i1)
                    .writeCell(p2)
                    .writeCell(q2)
                    .writeCell(i2)
                    .writeCell(flowTransfer);
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
