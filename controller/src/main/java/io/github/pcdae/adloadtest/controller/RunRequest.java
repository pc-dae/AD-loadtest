package io.github.pcdae.adloadtest.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** A validated request to start a test run. Every field ends up as a JMeter -J property. */
record RunRequest(String profile, int duration, int rampup, int ldapThreads, int kerberosThreads,
                  int ntlmThreads, int thinkMs, String authzBindAs) {

    static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.properties");

    static RunRequest fromForm(Map<String, String> form) {
        String profile = form.getOrDefault("profile", "lab.properties");
        if (!PROFILE_NAME.matcher(profile).matches()) {
            throw new IllegalArgumentException("Invalid profile name");
        }
        String bindAs = form.getOrDefault("authzBindAs", "user");
        if (!bindAs.equals("user") && !bindAs.equals("service")) {
            throw new IllegalArgumentException("authzBindAs must be 'user' or 'service'");
        }
        RunRequest r = new RunRequest(profile,
                intField(form, "duration", 120, 1, 86_400),
                intField(form, "rampup", 10, 0, 3_600),
                intField(form, "ldapThreads", 5, 0, 5_000),
                intField(form, "kerberosThreads", 5, 0, 5_000),
                intField(form, "ntlmThreads", 5, 0, 5_000),
                intField(form, "thinkMs", 0, 0, 60_000),
                bindAs);
        if (r.ldapThreads + r.kerberosThreads + r.ntlmThreads == 0) {
            throw new IllegalArgumentException("At least one protocol needs threads");
        }
        return r;
    }

    private static int intField(Map<String, String> form, String name, int def, int min, int max) {
        String raw = form.get(name);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    /** JMeter -J overrides for this run. */
    Map<String, String> jmeterProperties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("duration", Integer.toString(duration));
        p.put("rampup", Integer.toString(rampup));
        p.put("ldap.threads", Integer.toString(ldapThreads));
        p.put("kerberos.threads", Integer.toString(kerberosThreads));
        p.put("ntlm.threads", Integer.toString(ntlmThreads));
        p.put("think.ms", Integer.toString(thinkMs));
        p.put("authz.bindAs", authzBindAs);
        return p;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profile", profile);
        m.put("duration", duration);
        m.put("rampup", rampup);
        m.put("ldapThreads", ldapThreads);
        m.put("kerberosThreads", kerberosThreads);
        m.put("ntlmThreads", ntlmThreads);
        m.put("thinkMs", thinkMs);
        m.put("authzBindAs", authzBindAs);
        return m;
    }

    void store(Properties props) {
        toMap().forEach((k, v) -> props.setProperty(k, String.valueOf(v)));
    }
}
