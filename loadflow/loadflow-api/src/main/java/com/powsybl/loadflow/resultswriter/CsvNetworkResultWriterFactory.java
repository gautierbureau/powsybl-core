/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.loadflow.resultswriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link NetworkResultWriterFactory} that writes one CSV part file per partition, per dataset, into a directory laid
 * out as a standard partitioned dataset: {@code <directory>/branches/part-0.csv}, {@code <directory>/buses/part-0.csv},
 * {@code <directory>/generators/part-0.csv}, ... The set of part files of a dataset forms the full result for that
 * dataset (the reader concatenates them). A dataset directory is only created if at least one row of that dataset is
 * written.
 *
 * @author (design proposal)
 */
public class CsvNetworkResultWriterFactory implements NetworkResultWriterFactory {

    private final Path directory;

    public CsvNetworkResultWriterFactory(Path directory) {
        this.directory = Objects.requireNonNull(directory);
    }

    @Override
    public NetworkResultWriter create(int partitionIndex) {
        return new CsvNetworkResultWriter(dataset -> newPartWriter(dataset, partitionIndex));
    }

    private Writer newPartWriter(String dataset, int partitionIndex) {
        try {
            Path datasetDirectory = directory.resolve(dataset);
            Files.createDirectories(datasetDirectory);
            return Files.newBufferedWriter(datasetDirectory.resolve("part-" + partitionIndex + ".csv"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
