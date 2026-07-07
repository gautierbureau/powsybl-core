/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.security.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link SecurityAnalysisResultWriterFactory} that writes one CSV part file per partition into a directory:
 * {@code part-0.csv}, {@code part-1.csv}, ... The set of files forms the full result (the reader concatenates them).
 *
 * @author (design proposal)
 */
public class CsvSecurityAnalysisResultWriterFactory implements SecurityAnalysisResultWriterFactory {

    private final Path directory;

    public CsvSecurityAnalysisResultWriterFactory(Path directory) {
        this.directory = Objects.requireNonNull(directory);
    }

    @Override
    public SecurityAnalysisResultWriter create(int partitionIndex) {
        try {
            Files.createDirectories(directory);
            BufferedWriter writer = Files.newBufferedWriter(directory.resolve("part-" + partitionIndex + ".csv"), StandardCharsets.UTF_8);
            return new CsvSecurityAnalysisResultWriter(writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
