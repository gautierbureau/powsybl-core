/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManager;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The columnar variant stores are shared by every object of a type, so a structural change from a worker thread
 * during a multi-threaded phase can resize a store under a concurrent reader and corrupt all of them at once,
 * silently. These tests cover the guard that turns that contract violation into an immediate failure, and pin
 * down the patterns the {@link VariantManager} contract explicitly allows, which must stay allowed.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
class VariantStoreStructuralModificationTest {

    private static <T> T onOtherThread(Callable<T> callable) throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<T> future = executor.submit(callable);
            return future.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void addingEquipmentFromAWorkerThreadDuringMultiThreadAccessIsRejected() throws Exception {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager variantManager = network.getVariantManager();
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        variantManager.allowVariantMultiThreadAccess(true);

        // a worker adding equipment allocates a store row, which may resize the store under the readers
        Throwable thrown = onOtherThread(() -> assertThrows(Throwable.class, () ->
                network.getVoltageLevel("VLLOAD").newLoad()
                        .setId("LATE").setBus("NLOAD").setP0(1).setQ0(1).add()));

        PowsyblException e = assertInstanceOf(PowsyblException.class, thrown);
        assertTrue(e.getMessage().contains("Structural modification"), e.getMessage());
        assertTrue(e.getMessage().contains("multi-thread variant access is enabled"), e.getMessage());
    }

    @Test
    void cloningAVariantFromAWorkerThreadDuringMultiThreadAccessIsRejected() throws Exception {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager variantManager = network.getVariantManager();
        variantManager.allowVariantMultiThreadAccess(true);

        Throwable thrown = onOtherThread(() -> assertThrows(Throwable.class, () ->
                variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "fromWorker")));
        assertInstanceOf(PowsyblException.class, thrown);
    }

    @Test
    void removingAVariantFromAWorkerThreadStaysAllowed() throws Exception {
        // supported pattern, pinned down by AbstractExceptionIsThrownWhenRemoveVariantAndWorkingVariantIsNotSet
        // in the TCK: removeVariant only drives reduce/delete, which touch no geometry, so it is not guarded
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager variantManager = network.getVariantManager();
        variantManager.allowVariantMultiThreadAccess(true);
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "s");

        onOtherThread(() -> {
            variantManager.removeVariant("s");
            return null;
        });
        assertEquals(1, variantManager.getVariantIds().size());
    }

    @Test
    void readingAndWritingVariantAttributesFromAWorkerThreadStaysAllowed() throws Exception {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager variantManager = network.getVariantManager();
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        variantManager.allowVariantMultiThreadAccess(true);

        // the whole point of the multi-threaded phase: a worker owns a variant and reads/writes it freely
        double p = onOtherThread(() -> {
            variantManager.setWorkingVariant("v2");
            network.getLoad("LOAD").getTerminal().setP(42d);
            network.getGenerator("GEN").setTargetP(123d);
            return network.getLoad("LOAD").getTerminal().getP();
        });
        assertEquals(42d, p, 0d);
    }

    @Test
    void structuralChangesFromTheOwningThreadStayAllowed() {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager variantManager = network.getVariantManager();
        variantManager.allowVariantMultiThreadAccess(true);

        // the contract explicitly allows this: variants are removed from the main thread once work is over,
        // and VariantManagerImplTest already relies on it
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        assertNotNull(network.getVoltageLevel("VLLOAD").newLoad()
                .setId("LATE").setBus("NLOAD").setP0(1).setQ0(1).add());
        variantManager.removeVariant("v2");
    }

    @Test
    void structuralChangesAfterHandingTheNetworkToAnotherThreadStayAllowed() throws Exception {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager variantManager = network.getVariantManager();
        variantManager.allowVariantMultiThreadAccess(true);
        variantManager.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v2");
        // multi-threaded phase over
        variantManager.removeVariant("v2");
        variantManager.allowVariantMultiThreadAccess(false);

        // sequential handoff to another thread must not be flagged: the guard is inert outside the phase
        String id = onOtherThread(() -> {
            network.getVoltageLevel("VLLOAD").newLoad().setId("LATER").setBus("NLOAD").setP0(1).setQ0(1).add();
            network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v3");
            return network.getLoad("LATER").getId();
        });
        assertEquals("LATER", id);
    }

    @Test
    void aRedundantAllowFromAWorkerDoesNotStealOwnership() throws Exception {
        Network network = EurostagTutorialExample1Factory.create();
        VariantManager variantManager = network.getVariantManager();
        variantManager.allowVariantMultiThreadAccess(true);

        // VariantManagerImplTest#testMultipleSetAllowMultiThreadTrue does exactly this
        onOtherThread(() -> {
            variantManager.allowVariantMultiThreadAccess(true);
            return null;
        });

        // ownership must still be with the thread that opened the phase
        assertNotNull(network.getVoltageLevel("VLLOAD").newLoad()
                .setId("LATE").setBus("NLOAD").setP0(1).setQ0(1).add());
    }
}
