/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.shortcircuit;

import com.powsybl.commons.extensions.AbstractExtendable;
import com.powsybl.contingency.violations.LimitViolation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author Coline Piloquet {@literal <coline.piloquet at rte-france.com>}
 */
abstract class AbstractFaultResult extends AbstractExtendable<FaultResult> implements FaultResult {

    private final Status status;

    private final Fault fault;

    private final double shortCircuitPower;

    private final Duration timeConstant;

    private final List<FeederResult> feederResults;

    // Lazily-built index of feederResults by connectable id, so getFeederCurrent is O(1) instead of a linear
    // scan (callers reading many feeders of a fault would otherwise be O(feeders^2)).
    private transient volatile Map<String, FeederResult> feederResultsById;

    private final List<LimitViolation> limitViolations;

    private final List<ShortCircuitBusResults> shortCircuitBusResults;

    protected AbstractFaultResult(Fault fault, Status status, double shortCircuitPower, Duration timeConstant, List<FeederResult> feederResults,
                               List<LimitViolation> limitViolations, List<ShortCircuitBusResults> shortCircuitBusResults) {
        this.fault = Objects.requireNonNull(fault);
        this.shortCircuitPower = shortCircuitPower;
        this.limitViolations = new ArrayList<>();
        if (limitViolations != null) {
            this.limitViolations.addAll(limitViolations);
        }
        this.timeConstant = timeConstant;
        this.shortCircuitBusResults = new ArrayList<>();
        if (shortCircuitBusResults != null) {
            this.shortCircuitBusResults.addAll(shortCircuitBusResults);
        }
        this.feederResults = new ArrayList<>();
        if (feederResults != null) {
            this.feederResults.addAll(feederResults);
        }
        this.status = Objects.requireNonNull(status);
    }

    @Override
    public Fault getFault() {
        return fault;
    }

    @Override
    public double getShortCircuitPower() {
        return shortCircuitPower;
    }

    @Override
    public List<FeederResult> getFeederResults() {
        return feederResults;
    }

    @Override
    public List<LimitViolation> getLimitViolations() {
        return limitViolations;
    }

    @Override
    public Duration getTimeConstant() {
        return timeConstant;
    }

    @Override
    public List<ShortCircuitBusResults> getShortCircuitBusResults() {
        return shortCircuitBusResults;
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public double getFeederCurrent(String feederId) {
        Map<String, FeederResult> index = feederResultsById;
        if (index == null) {
            index = new HashMap<>();
            for (FeederResult feederResult : feederResults) {
                // keep the first result per id, matching the former first-match linear scan
                index.putIfAbsent(feederResult.getConnectableId(), feederResult);
            }
            feederResultsById = index;
        }
        FeederResult feederResult = index.get(feederId);
        if (feederResult == null) {
            return Double.NaN;
        }
        if (feederResult instanceof FortescueFeederResult fortescueFeederResult) {
            return fortescueFeederResult.getCurrent().getPositiveMagnitude();
        } else {
            return ((MagnitudeFaultResult) feederResult).getCurrent();
        }
    }

}
