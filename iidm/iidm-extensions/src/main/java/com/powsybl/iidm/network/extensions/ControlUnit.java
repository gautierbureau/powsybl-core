/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.extensions;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface ControlUnit {

    record ParticipateEvent(String controlZoneName, String controlUnitId, boolean value) {
    }

    record ParticipationFactorEvent(String controlZoneName, String controlUnitId, double value) {
    }

    String getId();

    boolean isParticipate();

    void setParticipate(boolean participate);

    /**
     * Participation factor of the unit in the reactive power sharing of its zone, in MVar (the reactive power the
     * unit provides when the zone level is 1, often noted Qr). NaN when not defined: the tools then use their own
     * default, for instance half of the reactive diagram range.
     */
    double getParticipationFactor();

    void setParticipationFactor(double participationFactor);
}
