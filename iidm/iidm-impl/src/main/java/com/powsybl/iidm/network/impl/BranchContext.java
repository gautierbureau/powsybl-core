/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p><b>Spike — structural variant / copy-on-write branching, cascade de-risking.</b> See
 * {@code structural-variant-cascade-design.md}.</p>
 *
 * <p>A structural branch's <em>terminal-attachment overrides</em>. When a line is split at a voltage
 * level that also hosts a through-connectable, the through-connectable is not recreated: only its
 * <em>near</em> terminal is rebound onto the branch-owned copy of the split voltage level. Its far
 * terminal is untouched, so nothing cascades into the far voltage level.</p>
 *
 * <p>This context records, per rebound terminal, the branch voltage level it resolves to (forward
 * direction), and per branch voltage level the set of shared terminals rebound onto it (reverse
 * direction). It is consulted only while active on the current thread (see
 * {@link ThreadLocalBranchContext}) and only for terminals flagged as rebound, so the base view and
 * the hot path are unaffected.</p>
 *
 * @author Claude
 */
final class BranchContext {

    private final Map<TerminalExt, VoltageLevelExt> voltageLevelOverride = new HashMap<>();
    private final Map<TerminalExt, Integer> nodeOverride = new HashMap<>();
    private final Map<VoltageLevelExt, Set<TerminalExt>> branchAttached = new HashMap<>();

    /** Rebind a shared terminal onto a branch-owned voltage level (bus/breaker, or node/breaker with no node change). */
    void rebind(TerminalExt terminal, VoltageLevelExt branchVoltageLevel) {
        rebind(terminal, branchVoltageLevel, null);
    }

    /**
     * Rebind a shared terminal onto a branch-owned voltage level, for this branch only. In node/breaker
     * a {@code branchNode} gives the terminal's node within the branch voltage level's graph; pass
     * {@code null} to keep the base node.
     */
    void rebind(TerminalExt terminal, VoltageLevelExt branchVoltageLevel, Integer branchNode) {
        voltageLevelOverride.put(terminal, branchVoltageLevel);
        if (branchNode != null) {
            nodeOverride.put(terminal, branchNode);
        }
        branchAttached.computeIfAbsent(branchVoltageLevel, k -> new LinkedHashSet<>()).add(terminal);
        // forward: the terminal now consults the context for its voltage level (and node)
        ((AbstractTerminal) terminal).setBranchAttachmentOverride(true);
        // reverse: the branch voltage level now unions branch-attached terminals in its enumeration
        ((VoltageLevelImpl) branchVoltageLevel).getTopologyModel().setBranchAttachmentHint(true);
    }

    /** Forward: the branch voltage level a rebound terminal resolves to, or {@code null} if not rebound. */
    VoltageLevelExt resolveVoltageLevel(TerminalExt terminal) {
        return voltageLevelOverride.get(terminal);
    }

    /** Forward (node/breaker): the branch node a rebound terminal resolves to, or {@code null} if unchanged. */
    Integer resolveNode(TerminalExt terminal) {
        return nodeOverride.get(terminal);
    }

    /** Reverse: the shared terminals rebound onto {@code voltageLevel} in this branch. */
    Set<TerminalExt> branchAttachedTerminals(VoltageLevelExt voltageLevel) {
        return branchAttached.getOrDefault(voltageLevel, Set.of());
    }

    /**
     * Reverse (bus view): the connected terminals rebound onto {@code voltageLevel} whose configured
     * bus is one of {@code busIds} — i.e. the branch-attached terminals that a merged bus over those
     * configured buses must include.
     */
    List<TerminalExt> branchAttachedConnectedTerminals(VoltageLevelExt voltageLevel, Set<String> busIds) {
        Set<TerminalExt> attached = branchAttached.get(voltageLevel);
        if (attached == null || attached.isEmpty()) {
            return List.of();
        }
        List<TerminalExt> result = new ArrayList<>();
        for (TerminalExt terminal : attached) {
            if (terminal instanceof BusTerminal busTerminal
                    && busIds.contains(busTerminal.getConnectableBusId()) && busTerminal.isConnected()) {
                result.add(terminal);
            }
        }
        return result;
    }

    /**
     * Reverse (node/breaker bus view): the connected terminals rebound onto {@code voltageLevel} whose
     * branch node is one of {@code nodes} — the branch-attached terminals a calculated bus over those
     * nodes must include.
     */
    List<TerminalExt> branchAttachedTerminalsOnNodes(VoltageLevelExt voltageLevel, Set<Integer> nodes) {
        Set<TerminalExt> attached = branchAttached.get(voltageLevel);
        if (attached == null || attached.isEmpty()) {
            return List.of();
        }
        List<TerminalExt> result = new ArrayList<>();
        for (TerminalExt terminal : attached) {
            if (terminal instanceof NodeTerminal nodeTerminal) {
                Integer override = nodeOverride.get(terminal);
                int branchNode = override != null ? override : nodeTerminal.getNode();
                if (nodes.contains(branchNode) && nodeTerminal.isConnected()) {
                    result.add(terminal);
                }
            }
        }
        return result;
    }
}
