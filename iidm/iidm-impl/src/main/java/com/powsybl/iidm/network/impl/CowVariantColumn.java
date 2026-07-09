/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>Spike v2.1b — copy-on-write variant state (de-risking prototype), one column.</b> See
 * {@code structural-variant-generic-design.md}, the v2.1 section.</p>
 *
 * <p>A single variant-dependent field (a generator target, a switch open flag, a terminal p/q, …) stored
 * <b>copy-on-write</b> over a {@link CowVariantParentage} instead of as a dense per-variant array. This is
 * the storage every {@code MultiVariantObject} would adopt to reach full network-store parity (v2.1b): a
 * variant holds a value only where it has actually <em>diverged</em>; otherwise a read falls through to
 * its parent variant. Two consequences:</p>
 * <ul>
 *   <li><b>Forking is O(1)</b> — {@link CowVariantParentage#fork} copies nothing, so a structural branch
 *       costs a parent pointer, not a per-object slot copy (this is exactly what v2.1a could not achieve:
 *       there {@code cloneVariant} extends every object's array, an O(N) copy).</li>
 *   <li><b>IIDM snapshot semantics are preserved</b> — the copy-on-write happens on the <em>write</em>
 *       side: before a variant's value diverges, the current value is frozen into the children that still
 *       inherit it, so a later change to a parent variant never leaks into a variant forked earlier (as a
 *       dense per-variant array would guarantee, and as {@code cloneVariant} promises today).</li>
 * </ul>
 *
 * <p>Cost moves from fork (was O(objects)) to a write of a <em>shared</em> variant (now O(direct children
 * that still inherit it), per column) — a good trade when branches are created far more often than base
 * variants are mutated after forking, the scenario/contingency workload.</p>
 *
 * @author Claude
 */
final class CowVariantColumn<T> {

    private final CowVariantParentage parentage;
    private final T rootDefault;
    // Sparse: only variants that have written (diverged) this field. Everything else inherits via parentage.
    private final Map<Integer, T> values = new HashMap<>();

    CowVariantColumn(CowVariantParentage parentage, T rootDefault) {
        this.parentage = parentage;
        this.rootDefault = rootDefault;
    }

    /** The value in {@code variant}: its own if it diverged, else the nearest ancestor's, else the root default. */
    T get(int variant) {
        int v = variant;
        while (v != -1) {
            if (values.containsKey(v)) {
                return values.get(v);
            }
            v = parentage.parent(v);
        }
        return rootDefault;
    }

    /**
     * Write {@code value} into {@code variant}. Copy-on-write: any direct child that still inherits this
     * variant's value is first frozen to the current value, so children forked before this write keep the
     * old value (IIDM snapshot semantics) — the change does not leak down.
     */
    void set(int variant, T value) {
        for (int child : parentage.children(variant)) {
            if (!values.containsKey(child)) {
                values.put(child, get(child));
            }
        }
        values.put(variant, value);
    }

    /** Number of variants that have actually diverged this field — the true storage cost (not the variant count). */
    int storedEntries() {
        return values.size();
    }
}
