/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;

import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class ThreadLocalMultiVariantContext implements VariantContext {

    private final ThreadLocal<Integer> index = ThreadLocal.withInitial(() -> null);

    // How many distinct threads have ever bound a working variant here. A thread must bind before it can
    // touch any variant-dependent state (getVariantIndex throws otherwise), so this counts every thread that
    // can be reading or writing a variant. It never decreases: a thread that has finished may still have
    // left the network in a state another thread observes, and over-counting only makes the guards that read
    // it more conservative. Reset happens by dropping the whole context when multi-thread access is disabled.
    private final AtomicInteger boundThreads = new AtomicInteger();

    // Never removed: it must survive reset()/resetIfVariantIndexIs so that re-binding does not count twice.
    private final ThreadLocal<Boolean> counted = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Override
    public int getVariantIndex() {
        Integer i = index.get();
        if (i == null) {
            throw new PowsyblException("Variant index not set for current thread " + Thread.currentThread().getName());
        }
        return i;
    }

    @Override
    public void setVariantIndex(int index) {
        if (Boolean.FALSE.equals(counted.get())) {
            counted.set(Boolean.TRUE);
            boundThreads.incrementAndGet();
        }
        this.index.set(index);
    }

    public void reset() {
        index.remove();
    }

    @Override
    public void resetIfVariantIndexIs(int index) {
        Integer i = this.index.get();
        if (i != null && i == index) {
            this.index.remove();
        }
    }

    @Override
    public boolean isIndexSet() {
        return this.index.get() != null;
    }

    @Override
    public boolean isSharedAcrossThreads() {
        return boundThreads.get() > 1;
    }

}
