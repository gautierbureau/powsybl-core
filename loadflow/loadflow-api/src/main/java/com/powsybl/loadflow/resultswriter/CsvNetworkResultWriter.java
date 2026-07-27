/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

import com.powsybl.commons.io.table.Column;
import com.powsybl.commons.io.table.CsvTableFormatter;
import com.powsybl.commons.io.table.TableFormatter;
import com.powsybl.commons.io.table.TableFormatterConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link NetworkResultWriter} that streams results to CSV outputs, one dataset per element type:
 * <ul>
 *     <li>{@code branches}: {@code stateId; subStateId; status; branchId; p1; q1; i1; p2; q2; i2; flowTransfer}</li>
 *     <li>{@code threeWindingsTransformers}: {@code stateId; subStateId; status; threeWindingsTransformerId; p1; q1; i1; p2; q2; i2; p3; q3; i3}</li>
 *     <li>{@code buses}: {@code stateId; subStateId; status; busId; v; angle}</li>
 *     <li>{@code generators}: {@code stateId; subStateId; status; generatorId; targetP; p}</li>
 * </ul>
 *
 * <p>The underlying {@link Writer} for a dataset is obtained lazily, on the first row of that dataset, from the
 * {@code writerProvider} passed at construction (keyed by the dataset name {@code "branches"} / {@code "buses"} /
 * {@code "generators"}). A producer that only emits some datasets (e.g. security analysis, branches only) never opens a
 * writer for the others.
 *
 * <p>CSV is intentionally the first, dependency-free output format. A columnar format such as Parquet is provided by a
 * separate implementation of {@link NetworkResultWriter}.
 *
 * @author (design proposal)
 */
public class CsvNetworkResultWriter implements NetworkResultWriter {

    static final String BRANCHES = "branches";
    static final String THREE_WINDINGS_TRANSFORMERS = "threeWindingsTransformers";
    static final String BUSES = "buses";
    static final String GENERATORS = "generators";

    private final Function<String, Writer> writerProvider;
    private final TableFormatterConfig config;

    private TableFormatter branchFormatter;
    private TableFormatter threeWindingsTransformerFormatter;
    private TableFormatter busFormatter;
    private TableFormatter generatorFormatter;

    public CsvNetworkResultWriter(Function<String, Writer> writerProvider) {
        // Locale.US so the decimal separator is '.' and there is no digit grouping, and ';' as field separator.
        this(writerProvider, new TableFormatterConfig(Locale.US, ';', "", true, false));
    }

    public CsvNetworkResultWriter(Function<String, Writer> writerProvider, TableFormatterConfig config) {
        this.writerProvider = Objects.requireNonNull(writerProvider);
        this.config = Objects.requireNonNull(config);
    }

    private TableFormatter branchFormatter() {
        if (branchFormatter == null) {
            branchFormatter = new CsvTableFormatter(writerProvider.apply(BRANCHES), "", config,
                    new Column("stateId"),
                    new Column("subStateId"),
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
        return branchFormatter;
    }

    private TableFormatter threeWindingsTransformerFormatter() {
        if (threeWindingsTransformerFormatter == null) {
            threeWindingsTransformerFormatter = new CsvTableFormatter(writerProvider.apply(THREE_WINDINGS_TRANSFORMERS), "", config,
                    new Column("stateId"),
                    new Column("subStateId"),
                    new Column("status"),
                    new Column("threeWindingsTransformerId"),
                    new Column("p1"),
                    new Column("q1"),
                    new Column("i1"),
                    new Column("p2"),
                    new Column("q2"),
                    new Column("i2"),
                    new Column("p3"),
                    new Column("q3"),
                    new Column("i3"));
        }
        return threeWindingsTransformerFormatter;
    }

    private TableFormatter busFormatter() {
        if (busFormatter == null) {
            busFormatter = new CsvTableFormatter(writerProvider.apply(BUSES), "", config,
                    new Column("stateId"),
                    new Column("subStateId"),
                    new Column("status"),
                    new Column("busId"),
                    new Column("v"),
                    new Column("angle"));
        }
        return busFormatter;
    }

    private TableFormatter generatorFormatter() {
        if (generatorFormatter == null) {
            generatorFormatter = new CsvTableFormatter(writerProvider.apply(GENERATORS), "", config,
                    new Column("stateId"),
                    new Column("subStateId"),
                    new Column("status"),
                    new Column("generatorId"),
                    new Column("targetP"),
                    new Column("p"));
        }
        return generatorFormatter;
    }

    @Override
    public void writeBranchResult(String stateId, String subStateId, String status, String branchId,
                                  double p1, double q1, double i1,
                                  double p2, double q2, double i2, double flowTransfer) {
        try {
            branchFormatter().writeCell(stateId)
                    .writeCell(subStateId)
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
    public void writeThreeWindingsTransformerResult(String stateId, String subStateId, String status,
                                                    String threeWindingsTransformerId,
                                                    double p1, double q1, double i1,
                                                    double p2, double q2, double i2,
                                                    double p3, double q3, double i3) {
        try {
            threeWindingsTransformerFormatter().writeCell(stateId)
                    .writeCell(subStateId)
                    .writeCell(status)
                    .writeCell(threeWindingsTransformerId)
                    .writeCell(p1)
                    .writeCell(q1)
                    .writeCell(i1)
                    .writeCell(p2)
                    .writeCell(q2)
                    .writeCell(i2)
                    .writeCell(p3)
                    .writeCell(q3)
                    .writeCell(i3);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeBusResult(String stateId, String subStateId, String status, String busId,
                               double v, double angle) {
        try {
            busFormatter().writeCell(stateId)
                    .writeCell(subStateId)
                    .writeCell(status)
                    .writeCell(busId)
                    .writeCell(v)
                    .writeCell(angle);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeGeneratorResult(String stateId, String subStateId, String status, String generatorId,
                                     double targetP, double p) {
        try {
            generatorFormatter().writeCell(stateId)
                    .writeCell(subStateId)
                    .writeCell(status)
                    .writeCell(generatorId)
                    .writeCell(targetP)
                    .writeCell(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (branchFormatter != null) {
                branchFormatter.close();
            }
            if (threeWindingsTransformerFormatter != null) {
                threeWindingsTransformerFormatter.close();
            }
            if (busFormatter != null) {
                busFormatter.close();
            }
            if (generatorFormatter != null) {
                generatorFormatter.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
