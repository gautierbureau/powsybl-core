/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.iidm.network.VariantManagerConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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

    // v2 (generic, don't-copy) machinery. branchDetached hides a base-graph terminal from a shared
    // voltage level in the branch view (symmetric to branchAttached, which shows a new terminal on it);
    // together they express a split as a terminal-membership delta over shared VLs, with no VL copy.
    // branchAttachTargets are the shared VLs onto which the connectable-add currently in progress must
    // branch-attach (record the new terminal in the context) instead of mutating the shared graph.
    private final Map<VoltageLevelExt, Set<TerminalExt>> branchDetached = new HashMap<>();
    private final Set<VoltageLevelExt> branchAttachTargets = new HashSet<>();

    // Per-branch state column: the base whose working variant is switched while this context is active,
    // and the base variant id the branch operates in. A branch reads/writes a shared object's variant
    // state (switch open, terminal p/q, tap positions, regulation setpoints...) in this variant, so it
    // carries its own operating point over the shared structure without materialising those objects.
    private final NetworkImpl base;
    private NetworkImpl branch;
    // A unified operating point pairs a base variant (for shared objects) with a branch variant (for
    // branch-owned objects), both under the same id, entered together in ThreadLocalBranchContext.run.
    private String stateVariantId;
    private String branchVariantId;

    BranchContext() {
        this(null);
    }

    BranchContext(NetworkImpl base) {
        this.base = base;
    }

    void setBranch(NetworkImpl branch) {
        this.branch = branch;
    }

    /** Fold a per-branch operating point (a base variant only) into this context; null clears it. */
    void setStateVariant(String baseVariantId) {
        this.stateVariantId = baseVariantId;
    }

    /**
     * Allocate a <em>unified</em> operating point: a variant of the same id in both the base (holding
     * the shared objects' state) and the branch (holding the branch-owned objects' state), each cloned
     * from its initial variant. Both networks are switched to thread-local variant access, so several
     * branches can hold distinct operating points concurrently on different threads. Entering the
     * context (run) then selects this operating point for the whole branch — shared and owned alike.
     */
    void allocateOperatingPoint(String operatingPointId) {
        cloneIfAbsent(base, operatingPointId);
        base.getVariantManager().allowVariantMultiThreadAccess(true);
        stateVariantId = operatingPointId;
        if (branch != null) {
            cloneIfAbsent(branch, operatingPointId);
            branch.getVariantManager().allowVariantMultiThreadAccess(true);
            branchVariantId = operatingPointId;
        }
    }

    private static void cloneIfAbsent(NetworkImpl network, String variantId) {
        if (!network.getVariantManager().getVariantIds().contains(variantId)) {
            network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, variantId);
        }
    }

    String getStateVariant() {
        return stateVariantId;
    }

    String getBranchVariant() {
        return branchVariantId;
    }

    NetworkImpl getBase() {
        return base;
    }

    NetworkImpl getBranch() {
        return branch;
    }

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

    // -------------------------------------------------------------------------------------------------
    // v2 (generic, don't-copy): branch-detached (hide a base terminal) + branch-attach add (add a
    // branch-owned terminal onto a shared VL without mutating its graph). See
    // structural-variant-generic-design.md.
    // -------------------------------------------------------------------------------------------------

    /**
     * Hide {@code terminal} from {@code voltageLevel} in this branch's view — the branch-detached
     * primitive. The terminal stays physically in the base graph; the folds subtract it. Used to remove
     * a split line's terminals from the shared endpoint VLs without touching the base.
     */
    void detach(TerminalExt terminal, VoltageLevelExt voltageLevel) {
        branchDetached.computeIfAbsent(voltageLevel, k -> new LinkedHashSet<>()).add(terminal);
        // activate the folds on this shared VL (same hint the reverse union uses; empty when no context)
        ((VoltageLevelImpl) voltageLevel).getTopologyModel().setBranchAttachmentHint(true);
    }

    /** The base-graph terminals hidden from {@code voltageLevel} in this branch. */
    Set<TerminalExt> branchDetachedTerminals(VoltageLevelExt voltageLevel) {
        return branchDetached.getOrDefault(voltageLevel, Set.of());
    }

    /** Bus view (bus/breaker): the connected branch-detached terminals of {@code voltageLevel} whose bus is in {@code busIds}. */
    List<TerminalExt> branchDetachedConnectedTerminals(VoltageLevelExt voltageLevel, Set<String> busIds) {
        Set<TerminalExt> detached = branchDetached.get(voltageLevel);
        if (detached == null || detached.isEmpty()) {
            return List.of();
        }
        List<TerminalExt> result = new ArrayList<>();
        for (TerminalExt terminal : detached) {
            if (terminal instanceof BusTerminal busTerminal
                    && busIds.contains(busTerminal.getConnectableBusId()) && busTerminal.isConnected()) {
                result.add(terminal);
            }
        }
        return result;
    }

    /** Bus view (node/breaker): the connected branch-detached terminals of {@code voltageLevel} on one of {@code nodes}. */
    List<TerminalExt> branchDetachedTerminalsOnNodes(VoltageLevelExt voltageLevel, Set<Integer> nodes) {
        Set<TerminalExt> detached = branchDetached.get(voltageLevel);
        if (detached == null || detached.isEmpty()) {
            return List.of();
        }
        List<TerminalExt> result = new ArrayList<>();
        for (TerminalExt terminal : detached) {
            if (terminal instanceof NodeTerminal nodeTerminal
                    && nodes.contains(nodeTerminal.getNode()) && nodeTerminal.isConnected()) {
                result.add(terminal);
            }
        }
        return result;
    }

    /**
     * Open a branch-attach window over {@code sharedVoltageLevels}: while open, a connectable-add whose
     * terminal targets one of these shared VLs records the terminal in this context (branch-attached)
     * instead of entering the shared VL's graph. Terminals targeting other (branch-owned) VLs attach
     * normally. Must be paired with {@link #endBranchAttach()}.
     */
    void beginBranchAttach(Collection<VoltageLevelExt> sharedVoltageLevels) {
        branchAttachTargets.addAll(sharedVoltageLevels);
    }

    void endBranchAttach() {
        branchAttachTargets.clear();
    }

    boolean isBranchAttachTarget(VoltageLevelExt voltageLevel) {
        return branchAttachTargets.contains(voltageLevel);
    }

    /**
     * Record {@code terminal} as branch-attached to the shared {@code voltageLevel} (reverse fold only:
     * the terminal's own voltage-level field already points at {@code voltageLevel}, so the forward
     * direction needs no override — unlike a rebind of a pre-existing shared terminal).
     */
    void branchAttach(TerminalExt terminal, VoltageLevelExt voltageLevel) {
        branchAttached.computeIfAbsent(voltageLevel, k -> new LinkedHashSet<>()).add(terminal);
        ((VoltageLevelImpl) voltageLevel).getTopologyModel().setBranchAttachmentHint(true);
    }
}
