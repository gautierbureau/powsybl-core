/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * <p><b>Spike v2.1a — variant-scoped terminal membership (de-risking prototype).</b> See
 * {@code structural-variant-generic-design.md}, the v2.1 section.</p>
 *
 * <p>The membership counterpart of {@link VariantScopedExistence}: it moves the {@code attached} /
 * {@code detached} terminal-membership delta v2 kept in an ambient {@link BranchContext} into
 * <b>per-variant</b> state on a single network. A voltage level's terminal set is then
 * {@code graph − detached(activeVariant) + attached(activeVariant)} — resolved against the
 * <b>working variant</b>, with no thread-local context. Combined with {@link VariantScopedExistence},
 * a split becomes pure variant state: the branch is a cloned variant, the working variant is the
 * context.</p>
 *
 * <p>Like existence, this is a {@link MultiVariantObject}: its per-variant membership maps are grown and
 * copied by the <em>real</em> variant lifecycle, so cloning a variant clones membership exactly as it
 * clones state. Registered only when structural branching is used; absent for normal networks, and the
 * folds consult it only when a voltage level is already flagged (branch-attachment hint), so the hot
 * path is unchanged.</p>
 *
 * @author Claude
 */
final class VariantScopedMembership implements MultiVariantObject {

    private final VariantManagerHolder holder;
    // Per variant index: for each voltage level, the terminals attached (shown) / detached (hidden) in
    // that variant. Most variants and VLs have no entry, so a fold is a couple of map lookups.
    private final Map<Integer, Map<VoltageLevelExt, Set<TerminalExt>>> attachedByVariant = new HashMap<>();
    private final Map<Integer, Map<VoltageLevelExt, Set<TerminalExt>>> detachedByVariant = new HashMap<>();
    private int variantArraySize;

    VariantScopedMembership(VariantManagerHolder holder, int variantArraySize) {
        this.holder = holder;
        this.variantArraySize = variantArraySize;
        for (int i = 0; i < variantArraySize; i++) {
            attachedByVariant.put(i, new HashMap<>());
            detachedByVariant.put(i, new HashMap<>());
        }
    }

    /** Show {@code terminal} on {@code voltageLevel} in the current working variant only. */
    void attachInCurrentVariant(VoltageLevelExt voltageLevel, TerminalExt terminal) {
        attachedByVariant.computeIfAbsent(holder.getVariantIndex(), k -> new HashMap<>())
                .computeIfAbsent(voltageLevel, k -> new LinkedHashSet<>()).add(terminal);
        ((VoltageLevelImpl) voltageLevel).getTopologyModel().setBranchAttachmentHint(true);
    }

    /** Hide {@code terminal} from {@code voltageLevel} in the current working variant only. */
    void detachInCurrentVariant(VoltageLevelExt voltageLevel, TerminalExt terminal) {
        detachedByVariant.computeIfAbsent(holder.getVariantIndex(), k -> new HashMap<>())
                .computeIfAbsent(voltageLevel, k -> new LinkedHashSet<>()).add(terminal);
        ((VoltageLevelImpl) voltageLevel).getTopologyModel().setBranchAttachmentHint(true);
    }

    /** Terminals shown on {@code voltageLevel} in the current working variant. */
    Set<TerminalExt> attachedTerminals(VoltageLevelExt voltageLevel) {
        return attachedByVariant.getOrDefault(holder.getVariantIndex(), Map.of())
                .getOrDefault(voltageLevel, Set.of());
    }

    /** Terminals hidden from {@code voltageLevel} in the current working variant. */
    Set<TerminalExt> detachedTerminals(VoltageLevelExt voltageLevel) {
        return detachedByVariant.getOrDefault(holder.getVariantIndex(), Map.of())
                .getOrDefault(voltageLevel, Set.of());
    }

    // --- MultiVariantObject: membership rides the real variant lifecycle, exactly like state ---

    private static Map<VoltageLevelExt, Set<TerminalExt>> deepCopy(Map<VoltageLevelExt, Set<TerminalExt>> source) {
        Map<VoltageLevelExt, Set<TerminalExt>> copy = new HashMap<>();
        source.forEach((vl, terminals) -> copy.put(vl, new LinkedHashSet<>(terminals)));
        return copy;
    }

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        for (int i = 0; i < number; i++) {
            attachedByVariant.put(initVariantArraySize + i, deepCopy(attachedByVariant.getOrDefault(sourceIndex, Map.of())));
            detachedByVariant.put(initVariantArraySize + i, deepCopy(detachedByVariant.getOrDefault(sourceIndex, Map.of())));
        }
        variantArraySize = initVariantArraySize + number;
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        for (int index : indexes) {
            attachedByVariant.put(index, deepCopy(attachedByVariant.getOrDefault(sourceIndex, Map.of())));
            detachedByVariant.put(index, deepCopy(detachedByVariant.getOrDefault(sourceIndex, Map.of())));
        }
    }

    @Override
    public void reduceVariantArraySize(int number) {
        for (int i = 0; i < number; i++) {
            variantArraySize--;
            attachedByVariant.remove(variantArraySize);
            detachedByVariant.remove(variantArraySize);
        }
    }

    @Override
    public void deleteVariantArrayElement(int index) {
        attachedByVariant.put(index, new HashMap<>());
        detachedByVariant.put(index, new HashMap<>());
    }
}
