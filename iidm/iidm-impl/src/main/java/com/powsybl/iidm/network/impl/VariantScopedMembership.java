/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p><b>Variant-scoped terminal membership.</b></p>
 *
 * <p>The membership counterpart of {@link VariantScopedExistence}: it holds the {@code attached} /
 * {@code detached} terminal-membership delta per variant. A voltage level's terminal set is
 * {@code graph − detached(activeVariant) + attached(activeVariant)} — resolved against the working variant.</p>
 *
 * <p>Like existence, membership is stored <b>copy-on-write</b> on the shared {@link VariantCowState}: a
 * variant records only the terminals whose membership it diverges from its parent; a read resolves through
 * the clone parentage until the first explicit entry or the dense initial variant. Like
 * {@link VariantScopedExistence}, the delta is live rather than a snapshot, matching how a network-store
 * partial variant resolves against its full variant.</p>
 *
 * <p>Registered on the network's first clone; absent while a network has a single variant, and the folds
 * consult it only when a voltage level is already flagged (branch-attachment hint), so the hot path is
 * unchanged.</p>
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
final class VariantScopedMembership implements MultiVariantObject {

    /** The shared base view: network-store's full variant. */
    private static final int BASE_VARIANT = 0;

    private final VariantManagerHolder holder;
    // Per variant, the terminals it diverges per voltage level: attached (shown) / detached (hidden). A
    // terminal with no entry in a variant inherits its membership through the clone parentage.
    private final Map<Integer, Map<VoltageLevelExt, Set<TerminalExt>>> attachedByVariant = new HashMap<>();
    private final Map<Integer, Map<VoltageLevelExt, Set<TerminalExt>>> detachedByVariant = new HashMap<>();
    // Voltage levels that carry any membership divergence anywhere — for eager materialisation enumeration.
    private final Set<VoltageLevelExt> scopedVoltageLevels = new LinkedHashSet<>();
    private int variantArraySize;
    // Transient (not variant-scoped) branch-attach window: the shared VLs onto which a connectable-add in
    // progress should record a branch-attach into the current variant instead of mutating the graph.
    private final Set<VoltageLevelExt> attachTargets = new HashSet<>();

    VariantScopedMembership(VariantManagerHolder holder, int variantArraySize) {
        this.holder = holder;
        this.variantArraySize = variantArraySize;
    }

    /** Show {@code terminal} on {@code voltageLevel} in the current working variant only. */
    void attachInCurrentVariant(VoltageLevelExt voltageLevel, TerminalExt terminal) {
        int current = holder.getVariantIndex();
        vlSet(attachedByVariant, current, voltageLevel).add(terminal);
        removeFrom(detachedByVariant, current, voltageLevel, terminal);
        scopedVoltageLevels.add(voltageLevel);
        ((VoltageLevelImpl) voltageLevel).getTopologyModel().setBranchAttachmentHint(true);
    }

    /** Hide {@code terminal} from {@code voltageLevel} in the current working variant only. */
    void detachInCurrentVariant(VoltageLevelExt voltageLevel, TerminalExt terminal) {
        int current = holder.getVariantIndex();
        vlSet(detachedByVariant, current, voltageLevel).add(terminal);
        removeFrom(attachedByVariant, current, voltageLevel, terminal);
        scopedVoltageLevels.add(voltageLevel);
        ((VoltageLevelImpl) voltageLevel).getTopologyModel().setBranchAttachmentHint(true);
    }

    /** Terminals shown on {@code voltageLevel} in the current working variant. */
    Set<TerminalExt> attachedTerminals(VoltageLevelExt voltageLevel) {
        return resolve(voltageLevel, holder.getVariantIndex()).attached;
    }

    /** Terminals hidden from {@code voltageLevel} in the current working variant. */
    Set<TerminalExt> detachedTerminals(VoltageLevelExt voltageLevel) {
        return resolve(voltageLevel, holder.getVariantIndex()).detached;
    }

    // The attached and detached terminals of a voltage level as resolved from variant v: the variant's own
    // entry wins, otherwise the shared base view answers. Two steps, never a walk -- see
    // VariantScopedExistence for why a partial variant never resolves through another one.
    private Resolved resolve(VoltageLevelExt voltageLevel, int v) {
        Set<TerminalExt> attached = new LinkedHashSet<>();
        Set<TerminalExt> detached = new LinkedHashSet<>();
        Set<TerminalExt> decided = identitySet();
        if (v != BASE_VARIANT) {
            collect(attachedByVariant, v, voltageLevel, decided, attached);
            collect(detachedByVariant, v, voltageLevel, decided, detached);
        }
        collect(attachedByVariant, BASE_VARIANT, voltageLevel, decided, attached);
        collect(detachedByVariant, BASE_VARIANT, voltageLevel, decided, detached);
        return new Resolved(attached, detached);
    }

    private static void collect(Map<Integer, Map<VoltageLevelExt, Set<TerminalExt>>> byVariant, int variant,
                                VoltageLevelExt voltageLevel, Set<TerminalExt> decided, Set<TerminalExt> out) {
        Map<VoltageLevelExt, Set<TerminalExt>> vlMap = byVariant.get(variant);
        if (vlMap == null) {
            return;
        }
        Set<TerminalExt> terminals = vlMap.get(voltageLevel);
        if (terminals == null) {
            return;
        }
        for (TerminalExt t : terminals) {
            if (decided.add(t)) { // nearest entry wins
                out.add(t);
            }
        }
    }

    private record Resolved(Set<TerminalExt> attached, Set<TerminalExt> detached) {
    }

    private static boolean contains(Map<Integer, Map<VoltageLevelExt, Set<TerminalExt>>> byVariant, int variant,
                                    VoltageLevelExt voltageLevel, TerminalExt terminal) {
        Map<VoltageLevelExt, Set<TerminalExt>> vlMap = byVariant.get(variant);
        if (vlMap == null) {
            return false;
        }
        Set<TerminalExt> terminals = vlMap.get(voltageLevel);
        return terminals != null && terminals.contains(terminal);
    }

    /** Freeze into copy-on-write children what they inherit from {@code variant} before it is removed. */

    private Set<VoltageLevelExt> divergedVoltageLevels(int variant) {
        Set<VoltageLevelExt> vls = new LinkedHashSet<>();
        Map<VoltageLevelExt, Set<TerminalExt>> att = attachedByVariant.get(variant);
        if (att != null) {
            vls.addAll(att.keySet());
        }
        Map<VoltageLevelExt, Set<TerminalExt>> det = detachedByVariant.get(variant);
        if (det != null) {
            vls.addAll(det.keySet());
        }
        return vls;
    }

    private static Set<TerminalExt> vlSet(Map<Integer, Map<VoltageLevelExt, Set<TerminalExt>>> byVariant,
                                          int variant, VoltageLevelExt voltageLevel) {
        return byVariant.computeIfAbsent(variant, k -> new HashMap<>())
                .computeIfAbsent(voltageLevel, k -> new LinkedHashSet<>());
    }

    private static void removeFrom(Map<Integer, Map<VoltageLevelExt, Set<TerminalExt>>> byVariant, int variant,
                                   VoltageLevelExt voltageLevel, TerminalExt terminal) {
        Map<VoltageLevelExt, Set<TerminalExt>> vlMap = byVariant.get(variant);
        if (vlMap != null) {
            Set<TerminalExt> terminals = vlMap.get(voltageLevel);
            if (terminals != null) {
                terminals.remove(terminal);
            }
        }
    }

    private static Set<TerminalExt> identitySet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Open a branch-attach window over {@code sharedVoltageLevels} for a connectable-add (see the topology-model intercept). */
    void beginAttach(Collection<VoltageLevelExt> sharedVoltageLevels) {
        attachTargets.addAll(sharedVoltageLevels);
    }

    void endAttach() {
        attachTargets.clear();
    }

    boolean isAttachTarget(VoltageLevelExt voltageLevel) {
        return attachTargets.contains(voltageLevel);
    }

    // Bus-view filters (current variant): the connected attached/detached terminals of a voltage level
    // whose configured bus is one of {@code busIds} (bus/breaker) or whose node is one of {@code nodes}
    // (node/breaker), for the bus-view folds.

    List<TerminalExt> attachedConnectedTerminals(VoltageLevelExt voltageLevel, Set<String> busIds) {
        return connectedOnBuses(attachedTerminals(voltageLevel), busIds);
    }

    List<TerminalExt> detachedConnectedTerminals(VoltageLevelExt voltageLevel, Set<String> busIds) {
        return connectedOnBuses(detachedTerminals(voltageLevel), busIds);
    }

    List<TerminalExt> attachedTerminalsOnNodes(VoltageLevelExt voltageLevel, Set<Integer> nodes) {
        return connectedOnNodes(attachedTerminals(voltageLevel), nodes);
    }

    List<TerminalExt> detachedTerminalsOnNodes(VoltageLevelExt voltageLevel, Set<Integer> nodes) {
        return connectedOnNodes(detachedTerminals(voltageLevel), nodes);
    }

    private static List<TerminalExt> connectedOnBuses(Set<TerminalExt> terminals, Set<String> busIds) {
        if (terminals.isEmpty()) {
            return List.of();
        }
        List<TerminalExt> result = new ArrayList<>();
        for (TerminalExt terminal : terminals) {
            if (terminal instanceof BusTerminal busTerminal
                    && busIds.contains(busTerminal.getConnectableBusId()) && busTerminal.isConnected()) {
                result.add(terminal);
            }
        }
        return result;
    }

    private static List<TerminalExt> connectedOnNodes(Set<TerminalExt> terminals, Set<Integer> nodes) {
        if (terminals.isEmpty()) {
            return List.of();
        }
        List<TerminalExt> result = new ArrayList<>();
        for (TerminalExt terminal : terminals) {
            if (terminal instanceof NodeTerminal nodeTerminal
                    && nodes.contains(nodeTerminal.getNode()) && nodeTerminal.isConnected()) {
                result.add(terminal);
            }
        }
        return result;
    }

    // --- MultiVariantObject: membership rides the real variant lifecycle, exactly like state ---

    @Override
    public void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex) {
        for (int i = 0; i < number; i++) {
            copyDelta(sourceIndex, initVariantArraySize + i);
        }
        variantArraySize = initVariantArraySize + number;
    }

    @Override
    public void allocateVariantArrayElement(int[] indexes, int sourceIndex) {
        for (int index : indexes) {
            // a recycled index starts clean, then takes over the source's delta
            attachedByVariant.remove(index);
            detachedByVariant.remove(index);
            copyDelta(sourceIndex, index);
        }
    }

    // Cloning from a variant that carries a delta copies that delta; cloning from the base copies nothing.
    private void copyDelta(int source, int target) {
        if (source == BASE_VARIANT || source == target) {
            return;
        }
        copyDelta(attachedByVariant, source, target);
        copyDelta(detachedByVariant, source, target);
    }

    private void copyDelta(Map<Integer, Map<VoltageLevelExt, Set<TerminalExt>>> byVariant, int source, int target) {
        Map<VoltageLevelExt, Set<TerminalExt>> vlMap = byVariant.get(source);
        if (vlMap == null) {
            return;
        }
        for (Map.Entry<VoltageLevelExt, Set<TerminalExt>> e : vlMap.entrySet()) {
            if (!e.getValue().isEmpty()) {
                vlSet(byVariant, target, e.getKey()).addAll(e.getValue());
            }
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
        attachedByVariant.remove(index);
        detachedByVariant.remove(index);
    }
}
