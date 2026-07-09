/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.Connectable;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * <p><b>Spike v2.1a — variant-scoped connectable add (type-agnostic).</b> See
 * {@code structural-variant-generic-design.md}, the v2.1 section.</p>
 *
 * <p>Adds <em>any</em> connectable — a generator, a load, a shunt, a line — so that it exists <b>only in
 * a given variant</b> of a single network, with <b>zero per-type code</b>. It is the general form of what
 * {@link VariantScopedLineSplit} does for the fault-on-line case: the whole variant-scoping machinery
 * ({@link VariantScopedExistence} for the object, {@link VariantScopedMembership} + the topology-model
 * branch-attach intercept for its terminals) is driven purely by ids and terminals, never by equipment
 * type — so the caller only supplies the ordinary adder for the type it wants.</p>
 *
 * <p>This is the concrete demonstration of the design's central claim: because a structural variant is a
 * terminal-membership + existence delta over shared voltage levels, it generalises to every equipment
 * type for free. Generators are just one instance.</p>
 *
 * @author Claude
 */
final class VariantScopedConnectableAdd {

    private VariantScopedConnectableAdd() {
    }

    /**
     * Run {@code adder} so that the connectable it creates exists only in {@code variantId} (cloned from
     * the current working variant if absent). Its terminals branch-attach onto {@code attachTargets} — the
     * shared voltage levels it connects to — in that variant, without mutating their graphs. Returns the
     * created connectable. The working variant is restored on exit.
     *
     * @param adder         the ordinary adder call, e.g. {@code () -> vl.newGenerator()...add()}
     * @param attachTargets the shared voltage levels the connectable attaches to (its endpoint VLs)
     */
    @SafeVarargs
    static <C extends Connectable<C>> C addInVariant(NetworkImpl network, String variantId,
                                                     Supplier<C> adder, VoltageLevelExt... attachTargets) {
        VariantScopedExistence existence = network.enableVariantScopedExistence();
        VariantScopedMembership membership = network.enableVariantScopedMembership();

        String source = network.getVariantManager().getWorkingVariantId();
        if (!network.getVariantManager().getVariantIds().contains(variantId)) {
            network.getVariantManager().cloneVariant(source, variantId);
        }
        network.getVariantManager().setWorkingVariant(variantId);
        try {
            membership.beginAttach(Arrays.asList(attachTargets));
            C connectable;
            try {
                connectable = adder.get();
            } finally {
                membership.endAttach();
            }
            existence.existOnlyInCurrentVariant(connectable.getId());
            return connectable;
        } finally {
            network.getVariantManager().setWorkingVariant(source);
        }
    }
}
