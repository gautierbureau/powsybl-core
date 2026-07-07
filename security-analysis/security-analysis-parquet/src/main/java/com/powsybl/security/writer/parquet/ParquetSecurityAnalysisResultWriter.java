/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.security.writer.parquet;

import blue.strategic.parquet.ParquetWriter;
import com.powsybl.security.writer.SecurityAnalysisResultWriter;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * A {@link SecurityAnalysisResultWriter} that streams monitored branch flows to a Parquet file.
 *
 * <p>One row is written per (contingency, branch), with columns {@code contingencyId, status, branchId, p1, q1, i1, p2,
 * q2, i2, flowTransfer}. The columnar layout compresses the highly repetitive {@code contingencyId}/{@code status}
 * columns very well and is well suited to the wide {@code branches x contingencies} result of a full security analysis.
 *
 * <p>Built on <a href="https://github.com/strategicblue/parquet-floor">parquet-floor</a>, a minimal Hadoop-free Parquet
 * writer. This writer is not thread-safe: use one instance per contingency partition (see
 * {@link ParquetSecurityAnalysisResultWriterFactory}) so that multi-threaded runs stay lock-free.
 *
 * @author (design proposal)
 */
public class ParquetSecurityAnalysisResultWriter implements SecurityAnalysisResultWriter {

    static final MessageType SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("contingencyId")
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

    private final ParquetWriter<ParquetSecurityAnalysisResultWriter> parquetWriter;

    // reused row state: a single mutable holder is written repeatedly, so there is no per-row object allocation.
    private String contingencyId;
    private String status;
    private String branchId;
    private double p1;
    private double q1;
    private double i1;
    private double p2;
    private double q2;
    private double i2;
    private double flowTransfer;

    public ParquetSecurityAnalysisResultWriter(File file) {
        Objects.requireNonNull(file);
        try {
            this.parquetWriter = ParquetWriter.writeFile(SCHEMA, file, (row, valueWriter) -> {
                valueWriter.write("contingencyId", row.contingencyId);
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeBranchResult(String contingencyId, String status, String branchId,
                                  double p1, double q1, double i1,
                                  double p2, double q2, double i2, double flowTransfer) {
        this.contingencyId = contingencyId;
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
            parquetWriter.write(this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            parquetWriter.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
