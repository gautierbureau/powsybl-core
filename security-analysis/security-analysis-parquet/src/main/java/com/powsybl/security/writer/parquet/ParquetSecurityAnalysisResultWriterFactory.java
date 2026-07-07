/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.security.writer.parquet;

import com.powsybl.security.writer.SecurityAnalysisResultWriter;
import com.powsybl.security.writer.SecurityAnalysisResultWriterFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link SecurityAnalysisResultWriterFactory} that writes one Parquet part file per partition into a directory:
 * {@code part-0.parquet}, {@code part-1.parquet}, ... The set of files forms a standard partitioned Parquet dataset.
 * Because each partition writes its own file, multi-threaded runs stream without locking.
 *
 * @author (design proposal)
 */
public class ParquetSecurityAnalysisResultWriterFactory implements SecurityAnalysisResultWriterFactory {

    private final Path directory;

    public ParquetSecurityAnalysisResultWriterFactory(Path directory) {
        this.directory = Objects.requireNonNull(directory);
    }

    @Override
    public SecurityAnalysisResultWriter create(int partitionIndex) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ParquetSecurityAnalysisResultWriter(directory.resolve("part-" + partitionIndex + ".parquet").toFile());
    }
}
