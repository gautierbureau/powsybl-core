/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Network;

import java.util.function.UnaryOperator;

/**
 * <p><b>Spike v2 — generic (don't-copy) structural branching: flatten-on-write.</b> See
 * {@code structural-variant-generic-design.md}.</p>
 *
 * <p>Where v1's {@link BranchFlattener} rebuilds the whole branch view type by type — the last
 * per-type class — v2 flattens with <b>zero per-type code</b> by composing two generic pieces:</p>
 * <ol>
 *   <li><b>copy the base</b> with a generic, all-type + extensions network copier — in production
 *       {@code NetworkSerDe::copy}, injected here because {@code NetworkSerDe} lives in the downstream
 *       {@code iidm-serde} module (this module depends only on {@code iidm-api});</li>
 *   <li><b>replay the structural delta</b> the branch recorded ({@link BranchContext#replayStructuralDelta})
 *       on that copy, via the public API — really remove the split line, really add the fictitious VL
 *       and the two half-lines.</li>
 * </ol>
 *
 * <p>The base copy is generic (maintained by IIDM's serializer), the delta replay is type-agnostic
 * (public-API mutations recorded by the operation), so this flattener dispatches on no equipment type.
 * The result is a plain, self-contained network — every element owned by it — that serializes fully,
 * and is byte-identical to {@code copy(base) + modify} by construction.</p>
 *
 * @author Claude
 */
final class BranchFlattenerV2 {

    private BranchFlattenerV2() {
    }

    /**
     * Flatten {@code branch} into a plain, self-contained network: copy its base with {@code baseCopier}
     * (production: {@code NetworkSerDe::copy}), then replay the branch's recorded structural delta on the
     * copy. Carries no per-type code.
     */
    static Network flatten(NetworkImpl branch, UnaryOperator<Network> baseCopier) {
        BranchContext context = branch.getBranchContext();
        Network flat = baseCopier.apply(context.getBase());
        context.replayStructuralDelta(flat);
        return flat;
    }
}
