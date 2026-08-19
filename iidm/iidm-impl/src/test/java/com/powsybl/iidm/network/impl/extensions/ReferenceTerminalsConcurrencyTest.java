/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl.extensions;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.extensions.ReferenceTerminals;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reference terminals are written by every AC load flow, on the variant it ran on, and several variants of
 * the same network can be computed concurrently. Those writes must not corrupt each other.
 *
 * @author Damien Jeandemange {@literal <damien.jeandemange at artelys.com>}
 */
class ReferenceTerminalsConcurrencyTest {

    private static final int VARIANT_COUNT = 24;
    private static final int ROUNDS = 300;

    @Test
    void oneVariantPerThread() throws Exception {
        Network network = EurostagTutorialExample1Factory.create();
        network.getVariantManager().allowVariantMultiThreadAccess(true);
        List<Terminal> terminals = network.getGeneratorStream().map(Generator::getTerminal).toList();

        List<String> variantIds = new ArrayList<>();
        for (int i = 0; i < VARIANT_COUNT; i++) {
            String variantId = "variant" + i;
            network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, variantId);
            variantIds.add(variantId);
        }

        ExecutorService executor = Executors.newFixedThreadPool(VARIANT_COUNT);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < VARIANT_COUNT; i++) {
                String variantId = variantIds.get(i);
                Terminal terminal = terminals.get(i % terminals.size());
                futures.add(executor.submit(() -> {
                    network.getVariantManager().setWorkingVariant(variantId);
                    for (int round = 0; round < ROUNDS; round++) {
                        // what a load flow does at the end of every run
                        ReferenceTerminals.reset(network);
                        ReferenceTerminals.addTerminal(terminal);
                        ReferenceTerminals.getTerminals(network);
                    }
                    return ReferenceTerminals.getTerminals(network).size();
                }));
            }
            for (Future<Integer> future : futures) {
                assertEquals(1, future.get(2, TimeUnit.MINUTES));
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
