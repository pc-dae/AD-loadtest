package io.github.pcdae.adloadtest.controller;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * A validated request to start a test run. Every load setting ends up as a JMeter property.
 *
 * <p>Rates (attempts per second) and attempt totals are for the whole run; the load generators
 * divide them between themselves. {@code targetHosts}, when set, replaces the profile's DCs.
 */
record RunRequest(String profile, String label, int duration, int rampup, int thinkMs, String authzBindAs,
                  List<Protocol> protocols, double badPasswordPct, int badPasswordMaxPerUser,
                  String targetHosts, int generators) {

    static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.properties");
    private static final Pattern LABEL = Pattern.compile("[\\p{L}\\p{N} ._:+()/-]{0,60}");
    private static final Pattern HOSTS = Pattern.compile("[A-Za-z0-9.-]+(,[A-Za-z0-9.-]+)*");

    /** Load for one protocol thread group; {@code key} is its JMeter property prefix. */
    record Protocol(String key, String name, int threads, double rate, long attempts) {
    }

    private static final String[][] PROTOCOLS = {
        {"ldap", "LDAP", "5"}, {"kerberos", "Kerberos", "5"}, {"ntlm", "NTLM", "5"}, {"computer", "Computers", "0"},
    };

    /** @param maxGenerators remote load generators available; 0 = JMeter runs in the controller */
    static RunRequest fromForm(Map<String, String> form, int maxGenerators) {
        String profile = form.getOrDefault("profile", "lab.properties");
        if (!PROFILE_NAME.matcher(profile).matches()) {
            throw new IllegalArgumentException("Invalid profile name");
        }
        String label = form.getOrDefault("label", "").trim();
        if (!LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("Label: up to 60 letters, digits, spaces and . _ : + ( ) / -");
        }
        String bindAs = form.getOrDefault("authzBindAs", "user");
        if (!bindAs.equals("user") && !bindAs.equals("service")) {
            throw new IllegalArgumentException("authzBindAs must be 'user' or 'service'");
        }
        String hosts = form.getOrDefault("targetHosts", "").replace(" ", "");
        if (!hosts.isEmpty() && !HOSTS.matcher(hosts).matches()) {
            throw new IllegalArgumentException("Target DCs: host names or IP addresses, comma separated");
        }
        List<Protocol> protocols = Arrays.stream(PROTOCOLS).map(p -> new Protocol(p[0], p[1],
                (int) number(form, p[0] + "Threads", Integer.parseInt(p[2]), 0, 5_000, true),
                number(form, p[0] + "Rate", 0, 0, 100_000, false),
                (long) number(form, p[0] + "Attempts", 0, 0, 100_000_000, true))).toList();
        if (protocols.stream().mapToInt(Protocol::threads).sum() == 0) {
            throw new IllegalArgumentException("At least one protocol needs threads");
        }
        RunRequest r = new RunRequest(profile, label,
                (int) number(form, "duration", 120, 1, 86_400, true),
                (int) number(form, "rampup", 10, 0, 3_600, true),
                (int) number(form, "thinkMs", 0, 0, 60_000, true),
                bindAs, protocols,
                number(form, "badPasswordPct", 0, 0, 100, false),
                (int) number(form, "badPasswordMaxPerUser", 0, 0, 1_000, true),
                hosts,
                (int) number(form, "generators", maxGenerators, maxGenerators == 0 ? 0 : 1, maxGenerators, true));
        for (Protocol p : protocols) {
            if (p.threads % r.generatorCount() != 0) {
                throw new IllegalArgumentException(p.name + " threads must be a multiple of the number of load generators ("
                        + r.generatorCount() + "), so each runs the same share");
            }
        }
        return r;
    }

    private static double number(Map<String, String> form, String name, double def, double min, double max, boolean whole) {
        String raw = form.get(name);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        double value;
        try {
            value = whole ? Long.parseLong(raw.trim()) : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a " + (whole ? "whole " : "") + "number");
        }
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException(name + " must be between " + fmt(min) + " and " + fmt(max));
        }
        return value;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    /** Remote generators share the run; without any, JMeter in the controller is the one generator. */
    int generatorCount() {
        return Math.max(1, generators);
    }

    /** JMeter properties for this run. Thread counts are per generator, so the run-wide count is split. */
    Map<String, String> jmeterProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("duration", Integer.toString(duration));
        p.put("rampup", Integer.toString(rampup));
        p.put("think.ms", Integer.toString(thinkMs));
        p.put("authz.bindAs", authzBindAs);
        for (Protocol proto : protocols) {
            p.put(proto.key + ".threads", Integer.toString(proto.threads / generatorCount()));
            p.put(proto.key + ".rate", fmt(proto.rate));
            p.put(proto.key + ".iterations", Long.toString(proto.attempts));
        }
        p.put("badPassword.percent", fmt(badPasswordPct));
        p.put("badPassword.maxPerUser", Integer.toString(badPasswordMaxPerUser));
        if (!targetHosts.isEmpty()) {
            p.put("ad.host", targetHosts);
        }
        p.put("generator.count", Integer.toString(generatorCount()));
        return p;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profile", profile);
        m.put("label", label);
        m.put("duration", duration);
        m.put("rampup", rampup);
        for (Protocol proto : protocols) {
            m.put(proto.key + "Threads", proto.threads);
            m.put(proto.key + "Rate", proto.rate);
            m.put(proto.key + "Attempts", proto.attempts);
        }
        m.put("thinkMs", thinkMs);
        m.put("authzBindAs", authzBindAs);
        m.put("badPasswordPct", badPasswordPct);
        m.put("badPasswordMaxPerUser", badPasswordMaxPerUser);
        m.put("targetHosts", targetHosts);
        m.put("generators", generatorCount());
        return m;
    }

    void store(Properties props) {
        toMap().forEach((k, v) -> props.setProperty(k, String.valueOf(v)));
    }
}
