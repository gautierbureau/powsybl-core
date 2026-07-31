package com.powsybl.iidm.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.test.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Audit: after a detach, every columnar store owner must have been re-homed into the detached network's
 * stores. An owner the cascade misses keeps pointing at the SOURCE network's store, which the detached
 * network's variant operations never extend - so reading it in a freshly cloned variant indexes past the
 * source store's single band. Cloning several variants and then touching every attribute of every object
 * turns any missed owner into an exception instead of a silent stale read.
 */
class ReHomeCascadeAuditTest {

    private static Map<String, Supplier<Network>> factories() {
        Map<String, Supplier<Network>> m = new LinkedHashMap<>();
        m.put("eurostag", EurostagTutorialExample1Factory::create);
        m.put("eurostagMoreGen", EurostagTutorialExample1Factory::createWithMoreGenerators);
        m.put("eurostagTieLine", EurostagTutorialExample1Factory::createWithTieLine);
        m.put("battery", BatteryNetworkFactory::create);
        m.put("boundaryLine", BoundaryLineNetworkFactory::create);
        m.put("fictitiousSwitch", FictitiousSwitchFactory::create);
        m.put("fourSubstations", FourSubstationsNodeBreakerFactory::create);
        m.put("fourSubstationsExt", FourSubstationsNodeBreakerWithExtensionsFactory::create);
        m.put("hvdcVsc", HvdcTestNetwork::createVsc);
        m.put("hvdcLcc", HvdcTestNetwork::createLcc);
        m.put("phaseShifter", PhaseShifterTestCaseFactory::create);
        m.put("shunt", ShuntTestCaseFactory::create);
        m.put("shuntNonLinear", ShuntTestCaseFactory::createNonLinear);
        m.put("svc", SvcTestCaseFactory::create);
        m.put("svcExt", SvcTestCaseFactory::createWithMoreSVCs);
        m.put("threeWindings", ThreeWindingsTransformerNetworkFactory::create);
        m.put("threeWindingsRtc", ThreeWindingsTransformerNetworkFactory::createWithCurrentLimits);
        m.put("reactiveLimits", ReactiveLimitsTestNetworkFactory::create);
        m.put("scada", ScadaNetworkFactory::create);
        m.put("securityAnalysis", SecurityAnalysisTestNetworkFactory::create);
        m.put("multipleExtensions", MultipleExtensionsTestNetworkFactory::create);
        m.put("dcLccMonopole", DcDetailedNetworkFactory::createLccMonopoleGroundReturn);
        m.put("dcLccBipole", DcDetailedNetworkFactory::createLccBipoleGroundReturn);
        m.put("dcLccMetallic", DcDetailedNetworkFactory::createLccMonopoleMetallicReturn);
        m.put("networkTest1", () -> NetworkTest1Factory.create("t1"));
        m.put("busBreakerTest1", NetworkBusBreakerTest1Factory::create);
        m.put("twoVoltageLevel", TwoVoltageLevelNetworkFactory::create);
        m.put("europeanLv", EuropeanLvTestFeederFactory::create);
        return m;
    }

    // Touch every variant-dependent attribute reachable through the public API.
    private static void readEverything(Network n) {
        n.getIdentifiables().forEach(i -> tolerantly(() -> readIdentifiable(i)));
    }

    private static void readIdentifiable(Identifiable<?> i) {
        if (i instanceof Connectable<?> c) {
            c.getTerminals().forEach(t -> tolerantly(() -> readTerminal(t)));
        }
        tolerantly(() -> readInjection(i));
        tolerantly(() -> readTapChangers(i));
        if (i instanceof Switch sw) {
            consume(sw.isOpen() || sw.isRetained());
        }
        if (i instanceof Bus bus) {
            consume(bus.getV() + bus.getAngle());
        }
        if (i instanceof Area a) {
            consume(a.getInterchangeTarget());
        }
        if (i instanceof HvdcLine h) {
            consume(h.getActivePowerSetpoint());
            consume(h.getConvertersMode());
        }
        if (i instanceof BoundaryLine dl) {
            consume(dl.getP0() + dl.getQ0());
        }
    }

    private static void readTerminal(Terminal t) {
        consume(t.getP());
        consume(t.getQ());
        consume(t.isConnected());
    }

    private static void readInjection(Identifiable<?> i) {
        if (i instanceof Generator g) {
            consume(g.getTargetP() + g.getTargetQ() + g.getTargetV());
            consume(g.isVoltageRegulatorOn());
        }
        if (i instanceof Load l) {
            consume(l.getP0() + l.getQ0());
        }
        if (i instanceof Battery b) {
            consume(b.getTargetP() + b.getTargetQ());
        }
        if (i instanceof ShuntCompensator sc) {
            consume(sc.getTargetV() + sc.getTargetDeadband());
            consume(sc.isVoltageRegulatorOn());
            consume(sc.getSectionCount());
        }
        if (i instanceof StaticVarCompensator svc) {
            consume(svc.getVoltageSetpoint() + svc.getReactivePowerSetpoint());
            consume(svc.getRegulationMode());
            consume(svc.isRegulating());
        }
        if (i instanceof VscConverterStation v) {
            consume(v.getVoltageSetpoint() + v.getReactivePowerSetpoint());
            consume(v.isVoltageRegulatorOn());
        }
    }

    private static void readTapChangers(Identifiable<?> i) {
        if (i instanceof TwoWindingsTransformer t) {
            t.getOptionalRatioTapChanger().ifPresent(tc -> tolerantly(() -> readTapChanger(tc)));
            t.getOptionalPhaseTapChanger().ifPresent(tc -> tolerantly(() -> readTapChanger(tc)));
        }
        if (i instanceof ThreeWindingsTransformer t) {
            t.getLegs().forEach(leg -> {
                leg.getOptionalRatioTapChanger().ifPresent(tc -> tolerantly(() -> readTapChanger(tc)));
                leg.getOptionalPhaseTapChanger().ifPresent(tc -> tolerantly(() -> readTapChanger(tc)));
            });
        }
    }

    private static void readTapChanger(TapChanger<?, ?, ?, ?> tc) {
        consume(tc.findTapPosition());
        consume(tc.getTargetDeadband());
        consume(tc.isRegulating());
    }

    private static void consume(Object o) {
        SINK[0] = o;
    }

    // Some factories (SCADA) deliberately leave values undefined, and their getters throw for that reason.
    // Tolerate only that: an IndexOutOfBoundsException is the symptom of a missed re-home and must still fail.
    private static void tolerantly(Runnable read) {
        try {
            read.run();
        } catch (PowsyblException e) {
            // expected for deliberately-undefined values; IndexOutOfBoundsException is a different
            // hierarchy and is deliberately not caught, so a missed re-home still fails the audit
        }
    }

    private static final Object[] SINK = new Object[1];

    @Test
    void everyStoreOwnerIsReHomedOnDetach() {
        StringBuilder failures = new StringBuilder();
        factories().forEach((name, factory) -> auditOne(name, factory, failures));
        if (failures.length() > 0) {
            fail("re-home cascade audit failures:" + failures);
        }
    }

    private static void auditOne(String name, Supplier<Network> factory, StringBuilder failures) {
        try {
            Network subject = factory.get();
            String subjectId = subject.getId();
            Network other = Network.create("other", "test");
            other.newSubstation().setId("OS").add()
                    .newVoltageLevel().setId("OVL").setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add()
                    .getBusBreakerView().newBus().setId("OB").add();

            Network merged;
            Network detached;
            if (subject.getSubnetworks().isEmpty()) {
                merged = Network.merge(subject, other);
                detached = merged.getSubnetwork(subjectId).detach();
            } else {
                // already a composite: detach one of its own subnetworks instead of merging
                merged = subject;
                detached = subject.getSubnetworks().iterator().next().detach();
            }

            // the source store keeps a single band; a missed owner still points at it
            VariantManager vm = detached.getVariantManager();
            for (int v = 1; v <= 5; v++) {
                vm.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v" + v);
            }
            vm.setWorkingVariant("v5");
            readEverything(detached);

            // and the remainder must still work too
            VariantManager vm2 = merged.getVariantManager();
            vm2.cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "w1");
            vm2.setWorkingVariant("w1");
            readEverything(merged);
        } catch (Exception | AssertionError e) {
            failures.append("\n  ").append(name).append(" -> ")
                    .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        }
    }
}
