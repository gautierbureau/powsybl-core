/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>Spike — structural variant / copy-on-write branching, cascade de-risking.</b></p>
 *
 * <p>Thread-scoped active {@link BranchContext}, mirroring {@code ThreadLocalMultiVariantContext} for
 * variants: a branch is "entered" on a thread, and structural resolution (a rebound terminal's
 * voltage level) consults the active context. When no context is active — the default for all normal
 * use — resolution falls back to the base, so behaviour is unchanged.</p>
 *
 * @author Claude
 */
final class ThreadLocalBranchContext {

    private static final ThreadLocal<BranchContext> CONTEXT = new ThreadLocal<>();

    private ThreadLocalBranchContext() {
    }

    static BranchContext get() {
        return CONTEXT.get();
    }

    /** Run {@code action} with {@code context} active on this thread, restoring the previous context after. */
    static void run(BranchContext context, Runnable action) {
        BranchContext previous = CONTEXT.get();
        CONTEXT.set(context);
        // Enter the branch's operating point: switch the base's working variant (shared objects) and,
        // for a unified operating point, the branch's working variant (branch-owned objects) to the
        // branch's column for the duration. Thread-safe when the managers are in thread-local variant
        // mode (see BranchContext.allocateOperatingPoint), so concurrent branches don't interfere.
        List<Runnable> restorers = new ArrayList<>(2);
        if (context != null) {
            if (context.getStateVariant() != null && context.getBase() != null) {
                restorers.add(enterVariant(context.getBase(), context.getStateVariant()));
            }
            if (context.getBranchVariant() != null && context.getBranch() != null) {
                restorers.add(enterVariant(context.getBranch(), context.getBranchVariant()));
            }
        }
        try {
            action.run();
        } finally {
            for (int i = restorers.size() - 1; i >= 0; i--) {
                restorers.get(i).run();
            }
            if (previous != null) {
                CONTEXT.set(previous);
            } else {
                CONTEXT.remove();
            }
        }
    }

    /** Switch a network's working variant, returning a restorer that undoes it (both variant modes). */
    private static Runnable enterVariant(NetworkImpl network, String variantId) {
        VariantContext variantContext = network.getVariantManager().getVariantContext();
        boolean wasSet = variantContext.isIndexSet();
        String saved = wasSet ? network.getVariantManager().getWorkingVariantId() : null;
        network.getVariantManager().setWorkingVariant(variantId);
        int enteredIndex = variantContext.getVariantIndex();
        return () -> {
            if (wasSet) {
                network.getVariantManager().setWorkingVariant(saved);
            } else {
                // thread-local mode with no prior variant on this thread: clear it back
                variantContext.resetIfVariantIndexIs(enteredIndex);
            }
        };
    }
}
