/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.structuralvariant;

import com.powsybl.iidm.network.Network;
import com.powsybl.matpower.model.MBranch;
import com.powsybl.matpower.model.MBus;
import com.powsybl.matpower.model.MGen;
import com.powsybl.matpower.model.MatpowerFormatVersion;
import com.powsybl.matpower.model.MatpowerModel;
import com.powsybl.matpower.model.MatpowerWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads large MATPOWER cases (the PEGASE series) as IIDM networks: downloads the {@code .m} text case
 * from the MATPOWER GitHub repository (cached under {@code target/matpower-cases}), parses the numeric
 * {@code mpc} tables, and round-trips through {@link MatpowerWriter} so the official powsybl MATPOWER
 * importer performs the IIDM conversion. Returns {@code null} when the case cannot be downloaded (offline
 * build) so callers can skip.
 *
 * @author Olivier Perrin {@literal <olivier.perrin at rte-france.com>}
 */
final class MatpowerCases {

    private static final String BASE_URL = "https://raw.githubusercontent.com/MATPOWER/matpower/master/data/";
    private static final Path CACHE = Path.of("target", "matpower-cases");

    private MatpowerCases() {
    }

    static Network load(String caseName) {
        try {
            Path mat = CACHE.resolve(caseName + ".mat");
            if (!Files.exists(mat)) {
                String m = download(caseName + ".m");
                if (m == null) {
                    return null;
                }
                MatpowerModel model = parse(caseName, m);
                Files.createDirectories(CACHE);
                MatpowerWriter.write(model, mat, false);
            }
            return Network.read(mat);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String download(String fileName) {
        Path cached = CACHE.resolve(fileName);
        try {
            if (Files.exists(cached)) {
                return Files.readString(cached);
            }
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + fileName)).timeout(Duration.ofMinutes(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Files.createDirectories(CACHE);
            Files.writeString(cached, response.body());
            return response.body();
        } catch (IOException e) {
            return null; // offline: caller skips
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // Minimal parser for the numeric tables of a MATPOWER .m case file (function form, format version 2).
    private static MatpowerModel parse(String caseName, String content) {
        MatpowerModel model = new MatpowerModel(caseName);
        model.setVersion(MatpowerFormatVersion.V2);
        String section = null;
        for (String rawLine : content.split("\n")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (section == null) {
                if (line.startsWith("mpc.baseMVA")) {
                    model.setBaseMva(Double.parseDouble(line.substring(line.indexOf('=') + 1).replace(";", "").trim()));
                } else if (line.startsWith("mpc.bus_name")) {
                    section = "skip";
                } else if (line.startsWith("mpc.bus")) {
                    section = "bus";
                } else if (line.startsWith("mpc.gencost")) {
                    section = "skip";
                } else if (line.startsWith("mpc.gen")) {
                    section = "gen";
                } else if (line.startsWith("mpc.branch")) {
                    section = "branch";
                } else if (line.startsWith("mpc.dcline")) {
                    section = "dcline";
                }
                continue;
            }
            if (line.startsWith("];") || line.startsWith("};")) {
                section = null;
                continue;
            }
            switch (section) {
                case "bus" -> model.addBus(toBus(numbers(line)));
                case "gen" -> model.addGenerator(toGen(numbers(line)));
                case "branch" -> model.addBranch(toBranch(numbers(line)));
                case "dcline" -> model.addDcLine(toDcLine(numbers(line)));
                default -> {
                    // skipped section
                }
            }
        }
        return model;
    }

    private static String stripComment(String line) {
        int i = line.indexOf('%');
        return i >= 0 ? line.substring(0, i) : line;
    }

    private static List<Double> numbers(String line) {
        List<Double> values = new ArrayList<>();
        for (String token : line.replace(";", " ").trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            values.add(switch (token) {
                case "Inf" -> Double.POSITIVE_INFINITY;
                case "-Inf" -> Double.NEGATIVE_INFINITY;
                default -> Double.parseDouble(token);
            });
        }
        return values;
    }

    // columns: bus_i type Pd Qd Gs Bs area Vm Va baseKV zone Vmax Vmin
    private static MBus toBus(List<Double> c) {
        MBus bus = new MBus();
        bus.setNumber((int) (double) c.get(0));
        bus.setType(switch ((int) (double) c.get(1)) {
            case 1 -> MBus.Type.PQ;
            case 2 -> MBus.Type.PV;
            case 3 -> MBus.Type.REF;
            default -> MBus.Type.ISOLATED;
        });
        bus.setRealPowerDemand(c.get(2));
        bus.setReactivePowerDemand(c.get(3));
        bus.setShuntConductance(c.get(4));
        bus.setShuntSusceptance(c.get(5));
        bus.setAreaNumber((int) (double) c.get(6));
        bus.setVoltageMagnitude(c.get(7));
        bus.setVoltageAngle(c.get(8));
        bus.setBaseVoltage(c.get(9));
        bus.setLossZone((int) (double) c.get(10));
        bus.setMaximumVoltageMagnitude(c.get(11));
        bus.setMinimumVoltageMagnitude(c.get(12));
        return bus;
    }

    // columns: bus Pg Qg Qmax Qmin Vg mBase status Pmax Pmin Pc1 Pc2 Qc1min Qc1max Qc2min Qc2max ...
    private static MGen toGen(List<Double> c) {
        MGen gen = new MGen();
        gen.setNumber((int) (double) c.get(0));
        gen.setRealPowerOutput(c.get(1));
        gen.setReactivePowerOutput(c.get(2));
        gen.setMaximumReactivePowerOutput(c.get(3));
        gen.setMinimumReactivePowerOutput(c.get(4));
        gen.setVoltageMagnitudeSetpoint(c.get(5));
        gen.setTotalMbase(c.get(6));
        gen.setStatus((int) (double) c.get(7));
        gen.setMaximumRealPowerOutput(c.get(8));
        gen.setMinimumRealPowerOutput(c.get(9));
        return gen;
    }

    // columns: fbus tbus status Pf Pt Qf Qt Vf Vt Pmin Pmax QminF QmaxF QminT QmaxT loss0 loss1
    private static com.powsybl.matpower.model.MDcLine toDcLine(List<Double> c) {
        com.powsybl.matpower.model.MDcLine dcLine = new com.powsybl.matpower.model.MDcLine();
        dcLine.setFrom((int) (double) c.get(0));
        dcLine.setTo((int) (double) c.get(1));
        dcLine.setStatus((int) (double) c.get(2));
        dcLine.setPf(c.get(3));
        dcLine.setPt(c.get(4));
        dcLine.setQf(c.get(5));
        dcLine.setQt(c.get(6));
        dcLine.setVf(c.get(7));
        dcLine.setVt(c.get(8));
        dcLine.setPmin(c.get(9));
        dcLine.setPmax(c.get(10));
        dcLine.setQminf(c.get(11));
        dcLine.setQmaxf(c.get(12));
        dcLine.setQmint(c.get(13));
        dcLine.setQmaxt(c.get(14));
        dcLine.setLoss0(c.get(15));
        dcLine.setLoss1(c.get(16));
        return dcLine;
    }

    // columns: fbus tbus r x b rateA rateB rateC ratio angle status angmin angmax
    private static MBranch toBranch(List<Double> c) {
        MBranch branch = new MBranch();
        branch.setFrom((int) (double) c.get(0));
        branch.setTo((int) (double) c.get(1));
        branch.setR(c.get(2));
        branch.setX(c.get(3));
        branch.setB(c.get(4));
        branch.setRateA(c.get(5));
        branch.setRateB(c.get(6));
        branch.setRateC(c.get(7));
        branch.setRatio(c.get(8));
        branch.setPhaseShiftAngle(c.get(9));
        branch.setStatus((int) (double) c.get(10));
        branch.setAngMin(c.get(11));
        branch.setAngMax(c.get(12));
        return branch;
    }
}
