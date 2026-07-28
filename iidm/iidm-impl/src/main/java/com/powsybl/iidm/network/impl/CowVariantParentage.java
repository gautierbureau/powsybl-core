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
 * <p><b>Prototype for the deferred columnar copy-on-write clone — the variant parentage. Not wired into
 * any live path; exercised only by unit tests.</b> See {@code structural-variant-public-api.md} (the
 * "O(1) clone" section).</p>
 *
 * <p>The variant-forest structure behind copy-on-write branching: each variant records the variant it was
 * forked from (its parent), or {@code -1} for a root. This is the direct analogue of network-store's
 * {@code fullVariantNum} — a variant points at the one it inherits from — and it makes forking a variant
 * <b>O(1)</b>: no per-object state is copied, only a parent pointer is recorded. A {@link CowVariantColumn}
 * resolves a read by walking this parentage until a variant that has actually written the value.</p>
 *
 * @author Claude
 */
final class CowVariantParentage {

    private final List<Integer> parentOf = new ArrayList<>();
    private final List<List<Integer>> childrenOf = new ArrayList<>();

    /** Create a root variant (no parent) and return its index. */
    int createRoot() {
        parentOf.add(-1);
        childrenOf.add(new ArrayList<>());
        return parentOf.size() - 1;
    }

    /** Fork a new variant from {@code parent} in O(1) — records a parent pointer, copies no state. */
    int fork(int parent) {
        int variant = parentOf.size();
        parentOf.add(parent);
        childrenOf.add(new ArrayList<>());
        childrenOf.get(parent).add(variant);
        return variant;
    }

    int parent(int variant) {
        return parentOf.get(variant);
    }

    List<Integer> children(int variant) {
        return childrenOf.get(variant);
    }

    int size() {
        return parentOf.size();
    }
}
