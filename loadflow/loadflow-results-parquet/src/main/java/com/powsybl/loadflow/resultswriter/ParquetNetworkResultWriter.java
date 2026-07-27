/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

import blue.strategic.parquet.Dehydrator;
import blue.strategic.parquet.ParquetWriter;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link NetworkResultWriter} that streams results to Parquet files, one dataset (schema) per element type: branch
 * flows, three-winding transformer flows, bus voltages and generator dispatch. Each dataset has its own {@code File},
 * obtained lazily on the first row
 * of that dataset from the {@code fileProvider} passed at construction (keyed by dataset name). A producer that only
 * emits some datasets (e.g. security analysis, branches only) never creates a file for the others.
 *
 * <p>The columnar layout compresses the highly repetitive {@code stateId}/{@code subStateId}/{@code status} columns
 * very well and is well suited to the wide {@code elements x states} result of a full security analysis or time-series
 * load flow.
 *
 * <p>Built on <a href="https://github.com/strategicblue/parquet-floor">parquet-floor</a>, a minimal Hadoop-free Parquet
 * writer. This writer is not thread-safe: use one instance per partition (see
 * {@link ParquetNetworkResultWriterFactory}) so that multi-threaded runs stay lock-free.
 *
 * @author (design proposal)
 */
public class ParquetNetworkResultWriter implements NetworkResultWriter {

    static final MessageType BRANCH_SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("stateId")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("subStateId")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("status")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("branchId")
            .required(PrimitiveTypeName.DOUBLE).named("p1")
            .required(PrimitiveTypeName.DOUBLE).named("q1")
            .required(PrimitiveTypeName.DOUBLE).named("i1")
            .required(PrimitiveTypeName.DOUBLE).named("p2")
            .required(PrimitiveTypeName.DOUBLE).named("q2")
            .required(PrimitiveTypeName.DOUBLE).named("i2")
            .required(PrimitiveTypeName.DOUBLE).named("flowTransfer")
            .named("branchFlow");

    static final MessageType THREE_WINDINGS_TRANSFORMER_SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("stateId")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("subStateId")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("status")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("threeWindingsTransformerId")
            .required(PrimitiveTypeName.DOUBLE).named("p1")
            .required(PrimitiveTypeName.DOUBLE).named("q1")
            .required(PrimitiveTypeName.DOUBLE).named("i1")
            .required(PrimitiveTypeName.DOUBLE).named("p2")
            .required(PrimitiveTypeName.DOUBLE).named("q2")
            .required(PrimitiveTypeName.DOUBLE).named("i2")
            .required(PrimitiveTypeName.DOUBLE).named("p3")
            .required(PrimitiveTypeName.DOUBLE).named("q3")
            .required(PrimitiveTypeName.DOUBLE).named("i3")
            .named("threeWindingsTransformerFlow");

    static final MessageType BUS_SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("stateId")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("subStateId")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("status")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("busId")
            .required(PrimitiveTypeName.DOUBLE).named("v")
            .required(PrimitiveTypeName.DOUBLE).named("angle")
            .named("busVoltage");

    static final MessageType GENERATOR_SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("stateId")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("subStateId")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("status")
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("generatorId")
            .required(PrimitiveTypeName.DOUBLE).named("targetP")
            .required(PrimitiveTypeName.DOUBLE).named("p")
            .named("generatorDispatch");

    private final Function<String, File> fileProvider;

    // One writer per dataset, created lazily. Only the datasets that receive at least one row are opened.
    private ParquetWriter<ParquetNetworkResultWriter> branchWriter;
    private ParquetWriter<ParquetNetworkResultWriter> threeWindingsTransformerWriter;
    private ParquetWriter<ParquetNetworkResultWriter> busWriter;
    private ParquetWriter<ParquetNetworkResultWriter> generatorWriter;

    // Reused row state: this instance is written repeatedly as the row holder for every dataset, so there is no per-row
    // object allocation. Each dataset's dehydrator only reads the fields of its own schema.
    private String stateId;
    private String subStateId;
    private String status;
    private String branchId;
    private double p1;
    private double q1;
    private double i1;
    private double p2;
    private double q2;
    private double i2;
    private double flowTransfer;
    private String threeWindingsTransformerId;
    private double p3;
    private double q3;
    private double i3;
    private String busId;
    private double v;
    private double angle;
    private String generatorId;
    private double targetP;
    private double p;

    public ParquetNetworkResultWriter(Function<String, File> fileProvider) {
        this.fileProvider = Objects.requireNonNull(fileProvider);
    }

    private ParquetWriter<ParquetNetworkResultWriter> branchWriter() {
        if (branchWriter == null) {
            branchWriter = writeFile(BRANCH_SCHEMA, CsvNetworkResultWriter.BRANCHES, (row, valueWriter) -> {
                valueWriter.write("stateId", row.stateId);
                valueWriter.write("subStateId", row.subStateId);
                valueWriter.write("status", row.status);
                valueWriter.write("branchId", row.branchId);
                valueWriter.write("p1", row.p1);
                valueWriter.write("q1", row.q1);
                valueWriter.write("i1", row.i1);
                valueWriter.write("p2", row.p2);
                valueWriter.write("q2", row.q2);
                valueWriter.write("i2", row.i2);
                valueWriter.write("flowTransfer", row.flowTransfer);
            });
        }
        return branchWriter;
    }

    private ParquetWriter<ParquetNetworkResultWriter> threeWindingsTransformerWriter() {
        if (threeWindingsTransformerWriter == null) {
            threeWindingsTransformerWriter = writeFile(THREE_WINDINGS_TRANSFORMER_SCHEMA,
                    CsvNetworkResultWriter.THREE_WINDINGS_TRANSFORMERS, (row, valueWriter) -> {
                        valueWriter.write("stateId", row.stateId);
                        valueWriter.write("subStateId", row.subStateId);
                        valueWriter.write("status", row.status);
                        valueWriter.write("threeWindingsTransformerId", row.threeWindingsTransformerId);
                        valueWriter.write("p1", row.p1);
                        valueWriter.write("q1", row.q1);
                        valueWriter.write("i1", row.i1);
                        valueWriter.write("p2", row.p2);
                        valueWriter.write("q2", row.q2);
                        valueWriter.write("i2", row.i2);
                        valueWriter.write("p3", row.p3);
                        valueWriter.write("q3", row.q3);
                        valueWriter.write("i3", row.i3);
                    });
        }
        return threeWindingsTransformerWriter;
    }

    private ParquetWriter<ParquetNetworkResultWriter> busWriter() {
        if (busWriter == null) {
            busWriter = writeFile(BUS_SCHEMA, CsvNetworkResultWriter.BUSES, (row, valueWriter) -> {
                valueWriter.write("stateId", row.stateId);
                valueWriter.write("subStateId", row.subStateId);
                valueWriter.write("status", row.status);
                valueWriter.write("busId", row.busId);
                valueWriter.write("v", row.v);
                valueWriter.write("angle", row.angle);
            });
        }
        return busWriter;
    }

    private ParquetWriter<ParquetNetworkResultWriter> generatorWriter() {
        if (generatorWriter == null) {
            generatorWriter = writeFile(GENERATOR_SCHEMA, CsvNetworkResultWriter.GENERATORS, (row, valueWriter) -> {
                valueWriter.write("stateId", row.stateId);
                valueWriter.write("subStateId", row.subStateId);
                valueWriter.write("status", row.status);
                valueWriter.write("generatorId", row.generatorId);
                valueWriter.write("targetP", row.targetP);
                valueWriter.write("p", row.p);
            });
        }
        return generatorWriter;
    }

    private ParquetWriter<ParquetNetworkResultWriter> writeFile(MessageType schema, String dataset,
                                                                Dehydrator<ParquetNetworkResultWriter> dehydrator) {
        try {
            return ParquetWriter.writeFile(schema, fileProvider.apply(dataset), dehydrator);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeBranchResult(String stateId, String subStateId, String status, String branchId,
                                  double p1, double q1, double i1,
                                  double p2, double q2, double i2, double flowTransfer) {
        this.stateId = stateId;
        this.subStateId = subStateId;
        this.status = status;
        this.branchId = branchId;
        this.p1 = p1;
        this.q1 = q1;
        this.i1 = i1;
        this.p2 = p2;
        this.q2 = q2;
        this.i2 = i2;
        this.flowTransfer = flowTransfer;
        try {
            branchWriter().write(this);
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
        this.stateId = stateId;
        this.subStateId = subStateId;
        this.status = status;
        this.threeWindingsTransformerId = threeWindingsTransformerId;
        this.p1 = p1;
        this.q1 = q1;
        this.i1 = i1;
        this.p2 = p2;
        this.q2 = q2;
        this.i2 = i2;
        this.p3 = p3;
        this.q3 = q3;
        this.i3 = i3;
        try {
            threeWindingsTransformerWriter().write(this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeBusResult(String stateId, String subStateId, String status, String busId,
                               double v, double angle) {
        this.stateId = stateId;
        this.subStateId = subStateId;
        this.status = status;
        this.busId = busId;
        this.v = v;
        this.angle = angle;
        try {
            busWriter().write(this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeGeneratorResult(String stateId, String subStateId, String status, String generatorId,
                                     double targetP, double p) {
        this.stateId = stateId;
        this.subStateId = subStateId;
        this.status = status;
        this.generatorId = generatorId;
        this.targetP = targetP;
        this.p = p;
        try {
            generatorWriter().write(this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (branchWriter != null) {
                branchWriter.close();
            }
            if (threeWindingsTransformerWriter != null) {
                threeWindingsTransformerWriter.close();
            }
            if (busWriter != null) {
                busWriter.close();
            }
            if (generatorWriter != null) {
                generatorWriter.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
