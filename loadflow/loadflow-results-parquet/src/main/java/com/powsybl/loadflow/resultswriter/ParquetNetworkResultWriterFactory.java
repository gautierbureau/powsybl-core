/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link NetworkResultWriterFactory} that writes one Parquet part file per partition, per dataset, into a directory
 * laid out as standard partitioned datasets: {@code <directory>/branches/part-0.parquet},
 * {@code <directory>/buses/part-0.parquet}, {@code <directory>/generators/part-0.parquet}, ... Because each partition
 * writes its own file per dataset, multi-threaded runs stream without locking. A dataset directory is only created if
 * at least one row of that dataset is written.
 *
 * @author (design proposal)
 */
public class ParquetNetworkResultWriterFactory implements NetworkResultWriterFactory {

    private final Path directory;

    public ParquetNetworkResultWriterFactory(Path directory) {
        this.directory = Objects.requireNonNull(directory);
    }

    @Override
    public NetworkResultWriter create(int partitionIndex) {
        return new ParquetNetworkResultWriter(dataset -> newPartFile(dataset, partitionIndex));
    }

    private File newPartFile(String dataset, int partitionIndex) {
        try {
            Path datasetDirectory = directory.resolve(dataset);
            Files.createDirectories(datasetDirectory);
            return datasetDirectory.resolve("part-" + partitionIndex + ".parquet").toFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
