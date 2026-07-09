/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.extensions.GeneratorShortCircuit;
import com.powsybl.iidm.network.extensions.GeneratorShortCircuitAdder;

/**
 * <p><b>Spike — structural variant / copy-on-write branching.</b> Copy extensions from a source object
 * onto its branch-owned copy during materialisation, so branch objects keep the attached data a
 * consumer needs.</p>
 *
 * <p>Short-circuit is the driving use case, so the short-circuit extensions are handled first:
 * {@link GeneratorShortCircuit} carries the fault-current reactances a fault-on-line study reads, and a
 * materialised generator would otherwise lose it. Extensions are per-type (there is no generic deep
 * copy in IIDM), so more types are added here one at a time. The general answer — treating every
 * extension as an "external attribute" with its own tombstone + full/partial merge — is what
 * powsybl-network-store does; see {@code structural-variant-vs-network-store.md}.</p>
 *
 * @author Claude
 */
final class BranchExtensionCopier {

    private BranchExtensionCopier() {
    }

    static void copy(Identifiable<?> source, Identifiable<?> target) {
        if (source instanceof Generator sourceGenerator && target instanceof Generator targetGenerator) {
            copyGeneratorShortCircuit(sourceGenerator, targetGenerator);
        }
    }

    private static void copyGeneratorShortCircuit(Generator source, Generator target) {
        GeneratorShortCircuit ext = source.getExtension(GeneratorShortCircuit.class);
        if (ext != null) {
            target.newExtension(GeneratorShortCircuitAdder.class)
                    .withDirectTransX(ext.getDirectTransX())
                    .withDirectSubtransX(ext.getDirectSubtransX())
                    .withStepUpTransformerX(ext.getStepUpTransformerX())
                    .add();
        }
    }
}
