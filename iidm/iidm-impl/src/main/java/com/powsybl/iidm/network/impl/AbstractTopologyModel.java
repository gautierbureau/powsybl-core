/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.google.common.collect.FluentIterable;
import com.google.common.primitives.Ints;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.util.ShortIdDictionary;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.dot.DOTSubgraph;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static com.powsybl.iidm.network.dot.IidmDOTUtils.exportGraph;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
abstract class AbstractTopologyModel extends AbstractPropertiesHolder implements TopologyModel {

    public static final int DEFAULT_NODE_INDEX_LIMIT = 1000;

    public static final int NODE_INDEX_LIMIT = loadNodeIndexLimit(PlatformConfig.defaultConfig());

    protected final VoltageLevelExt voltageLevel;

    protected AbstractTopologyModel(VoltageLevelExt voltageLevel) {
        this.voltageLevel = Objects.requireNonNull(voltageLevel);
    }

    protected static int loadNodeIndexLimit(PlatformConfig platformConfig) {
        return platformConfig
                .getOptionalModuleConfig("iidm")
                .map(moduleConfig -> moduleConfig.getIntProperty("node-index-limit", DEFAULT_NODE_INDEX_LIMIT))
                .orElse(DEFAULT_NODE_INDEX_LIMIT);
    }

    protected NetworkImpl getNetwork() {
        return voltageLevel.getNetwork();
    }

    protected static void addNextTerminals(TerminalExt otherTerminal, List<TerminalExt> nextTerminals) {
        Objects.requireNonNull(otherTerminal);
        Objects.requireNonNull(nextTerminals);
        Connectable<?> otherConnectable = otherTerminal.getConnectable();
        if (otherConnectable instanceof Branch<?> branch) {
            if (branch.getTerminal1() == otherTerminal) {
                nextTerminals.add((TerminalExt) branch.getTerminal2());
            } else if (branch.getTerminal2() == otherTerminal) {
                nextTerminals.add((TerminalExt) branch.getTerminal1());
            } else {
                throw new IllegalStateException();
            }
        } else if (otherConnectable instanceof ThreeWindingsTransformer ttc) {
            if (ttc.getLeg1().getTerminal() == otherTerminal) {
                nextTerminals.add((TerminalExt) ttc.getLeg2().getTerminal());
                nextTerminals.add((TerminalExt) ttc.getLeg3().getTerminal());
            } else if (ttc.getLeg2().getTerminal() == otherTerminal) {
                nextTerminals.add((TerminalExt) ttc.getLeg1().getTerminal());
                nextTerminals.add((TerminalExt) ttc.getLeg3().getTerminal());
            } else if (ttc.getLeg3().getTerminal() == otherTerminal) {
                nextTerminals.add((TerminalExt) ttc.getLeg1().getTerminal());
                nextTerminals.add((TerminalExt) ttc.getLeg2().getTerminal());
            } else {
                throw new IllegalStateException();
            }
        }
    }

    public void invalidateCache() {
        invalidateCache(false);
    }

    public abstract Iterable<Terminal> getTerminals();

    public abstract Stream<Terminal> getTerminalStream();

    // Spike (structural-variant branching): when true, this voltage level has a variant-scoped terminal
    // membership delta, so enumeration folds in the active variant's attached/detached terminals. Default
    // false -> the common path is exactly getTerminals().
    private boolean branchAttachmentHint = false;

    void setBranchAttachmentHint(boolean branchAttachmentHint) {
        this.branchAttachmentHint = branchAttachmentHint;
    }

    private Iterable<Terminal> terminalsWithBranchAttached() {
        Set<TerminalExt> attached = branchAttachedTerminals();
        Set<TerminalExt> detached = branchDetachedTerminals();
        if (attached.isEmpty() && detached.isEmpty()) {
            return getTerminals();
        }
        FluentIterable<Terminal> result = FluentIterable.from(getTerminals());
        if (!detached.isEmpty()) {
            result = FluentIterable.from(result.filter(t -> !detached.contains(t)));
        }
        return attached.isEmpty() ? result
                : result.append(FluentIterable.from(attached).transform(t -> (Terminal) t));
    }

    private Stream<Terminal> terminalStreamWithBranchAttached() {
        Set<TerminalExt> attached = branchAttachedTerminals();
        Set<TerminalExt> detached = branchDetachedTerminals();
        if (attached.isEmpty() && detached.isEmpty()) {
            return getTerminalStream();
        }
        Stream<Terminal> own = getTerminalStream();
        if (!detached.isEmpty()) {
            own = own.filter(t -> !detached.contains(t));
        }
        return attached.isEmpty() ? own : Stream.concat(own, attached.stream().map(t -> (Terminal) t));
    }

    private Set<TerminalExt> branchAttachedTerminals() {
        if (!branchAttachmentHint) {
            return Set.of();
        }
        VariantScopedMembership membership = getNetwork().getVariantScopedMembership();
        return membership == null ? Set.of() : membership.attachedTerminals(voltageLevel);
    }

    private Set<TerminalExt> branchDetachedTerminals() {
        if (!branchAttachmentHint) {
            return Set.of();
        }
        VariantScopedMembership membership = getNetwork().getVariantScopedMembership();
        return membership == null ? Set.of() : membership.detachedTerminals(voltageLevel);
    }

    /** This voltage level is a shared VL currently receiving a branch-attach add in the active variant. */
    protected boolean isActiveBranchAttachTarget() {
        VariantScopedMembership membership = getNetwork().getVariantScopedMembership();
        return membership != null && membership.isAttachTarget(voltageLevel);
    }

    /**
     * Branch-attach add: when a connectable-add targets this (shared) voltage level under an active
     * branch-attach window, record the just-created terminal as branch-attached in the active variant's
     * membership instead of entering this VL's graph — the shared graph is not mutated. Returns
     * {@code true} if handled.
     */
    protected boolean branchAttachIntercept(TerminalExt terminal) {
        VariantScopedMembership membership = getNetwork().getVariantScopedMembership();
        if (membership != null && membership.isAttachTarget(voltageLevel)) {
            terminal.setVoltageLevel(voltageLevel);
            membership.attachInCurrentVariant(voltageLevel, terminal);
            return true;
        }
        return false;
    }

    public <T extends Connectable> Iterable<T> getConnectables(Class<T> clazz) {
        return FluentIterable.from(terminalsWithBranchAttached())
                .transform(Terminal::getConnectable)
                .filter(clazz)
                .toSet();
    }

    public <T extends Connectable> Stream<T> getConnectableStream(Class<T> clazz) {
        return terminalStreamWithBranchAttached()
                .map(Terminal::getConnectable)
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .distinct();
    }

    public <T extends Connectable> int getConnectableCount(Class<T> clazz) {
        return Ints.checkedCast(getConnectableStream(clazz).count());
    }

    public Iterable<Connectable> getConnectables() {
        return FluentIterable.from(terminalsWithBranchAttached())
                .transform(Terminal::getConnectable)
                .toSet();
    }

    public Stream<Connectable> getConnectableStream() {
        return terminalStreamWithBranchAttached()
                .map(Terminal::getConnectable)
                .distinct();
    }

    public abstract VoltageLevelExt.NodeBreakerViewExt getNodeBreakerView();

    public abstract VoltageLevelExt.BusBreakerViewExt getBusBreakerView();

    public abstract VoltageLevelExt.BusViewExt getBusView();

    public abstract Iterable<Switch> getSwitches();

    public abstract int getSwitchCount();

    public abstract TopologyKind getTopologyKind();

    public abstract void extendVariantArraySize(int initVariantArraySize, int number, int sourceIndex);

    public abstract void reduceVariantArraySize(int number);

    public abstract void deleteVariantArrayElement(int index);

    public abstract void allocateVariantArrayElement(int[] indexes, int sourceIndex);

    protected abstract void removeTopology();

    public abstract void printTopology();

    public abstract void printTopology(PrintStream out, ShortIdDictionary dict);

    public abstract void exportTopology(Path file) throws IOException;

    public abstract void exportTopology(Writer writer);

    public void exportTopology(Writer writer, Random random) {
        Objects.requireNonNull(writer);
        Objects.requireNonNull(random);
        Map<String, Attribute> graphAttributes = new HashMap<>();
        exportGraph(writer, random, this::exportVertices, this::exportEdges, graphAttributes);
    }

    protected abstract void exportVertices(Map<String, Map<String, Attribute>> vertexAttributes,
                                           Map<DefaultEdge, Map<String, Attribute>> edgeAttributes,
                                           Random random,
                                           Graph<String, DefaultEdge> jGraph,
                                           Map<String, DOTSubgraph<String, DefaultEdge>> subgraphs);

    protected abstract void exportEdges(Map<DefaultEdge, Map<String, Attribute>> edgeAttributes,
                                        Graph<String, DefaultEdge> jGraph);
}
