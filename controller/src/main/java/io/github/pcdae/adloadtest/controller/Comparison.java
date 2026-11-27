package io.github.pcdae.adloadtest.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Side-by-side comparison of two finished runs, e.g. a baseline and the same load with a security agent
 * installed on the domain controllers.
 *
 * <p>Response times and errors per transaction come from each run's JMeter report (statistics.json).
 * Domain controller resource use comes from the Telegraf database in InfluxDB, averaged over each run's
 * steady state (after ramp-up): Windows performance counters scraped from the DCs, or the Samba
 * container's CPU and memory in the local lab.
 */
final class Comparison {

    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._-]+");

    /** What the response-time columns of statistics.json are, in the report's default percentiles. */
    private static final Map<String, String> STATS = linked(
            "sampleCount", "samples", "errorPct", "errorPct", "throughput", "throughput",
            "meanResTime", "mean", "medianResTime", "median", "pct1ResTime", "p90", "pct2ResTime", "p95",
            "pct3ResTime", "p99", "maxResTime", "max");

    private record Metric(String name, String unit, String agg, String measurement, String field,
                          String where, String hostTag) {
    }

    private static final String WIN = "dc";
    private static final String DOCKER_DC = "\"container_name\" =~ /-dc[0-9]/";
    private static final List<Metric> DC_METRICS = List.of(
            win("CPU", "percent", "mean", "win_cpu_Percent_Processor_Time"),
            win("LSASS CPU", "percent", "mean", "win_proc_Percent_Processor_Time"),
            win("LSASS working set", "bytes", "max", "win_proc_Working_Set"),
            win("Available memory", "MB", "min", "win_mem_Available_MBytes"),
            win("LDAP binds", "/s", "mean", "win_ntds_LDAP_Successful_Binds_persec"),
            win("LDAP searches", "/s", "mean", "win_ntds_LDAP_Searches_persec"),
            win("LDAP bind time", "ms", "mean", "win_ntds_LDAP_Bind_Time"),
            win("Kerberos authentications", "/s", "mean", "win_auth_Kerberos_Authentications"),
            win("NTLM authentications", "/s", "mean", "win_auth_NTLM_Authentications"),
            win("ATQ request latency", "ms", "mean", "win_ntds_ATQ_Request_Latency"),
            win("ATQ queued requests", "", "max", "win_ntds_ATQ_Outstanding_Queued_Requests"),
            win("Netlogon semaphore waiters", "", "max", "win_netlogon_Semaphore_Waiters"),
            win("Netlogon semaphore timeouts", "", "spread", "win_netlogon_Semaphore_Timeouts"),
            new Metric("CPU", "percent", "mean", "docker_container_cpu", "usage_percent",
                    DOCKER_DC + " AND \"cpu\" = 'cpu-total'", "container_name"),
            new Metric("Memory", "bytes", "max", "docker_container_mem", "usage", DOCKER_DC, "container_name"));

    private final Path resultsDir;
    private final String influxUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Comparison(Path resultsDir, String influxUrl) {
        this.resultsDir = resultsDir;
        this.influxUrl = influxUrl.replaceAll("/+$", "");
    }

    private static Metric win(String name, String unit, String agg, String field) {
        return new Metric(name, unit, agg, WIN, field, null, "host");
    }

    @SafeVarargs
    private static <T> Map<T, T> linked(T... kv) {
        Map<T, T> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    Map<String, Object> compare(String a, String b) throws IOException {
        RunData runA = load(a);
        RunData runB = load(b);

        Set<String> names = new LinkedHashSet<>();
        names.add("Total");
        names.addAll(runA.stats.keySet().stream().sorted().toList());
        names.addAll(runB.stats.keySet().stream().sorted().toList());
        List<Map<String, Object>> transactions = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("transaction", name);
            row.put("a", runA.stats.get(name));
            row.put("b", runB.stats.get(name));
            transactions.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", runA.summary());
        out.put("b", runB.summary());
        out.put("transactions", transactions);
        List<Map<String, Object>> dc = new ArrayList<>();
        String dcError = null;
        if (!influxUrl.isEmpty()) {
            try {
                Map<String, Map<String, Double>> dcA = dcMetrics(runA);
                Map<String, Map<String, Double>> dcB = dcMetrics(runB);
                for (Metric m : DC_METRICS) {
                    String key = m.measurement + "/" + m.name;
                    Set<String> hosts = new TreeSet<>();
                    hosts.addAll(dcA.getOrDefault(key, Map.of()).keySet());
                    hosts.addAll(dcB.getOrDefault(key, Map.of()).keySet());
                    for (String host : hosts) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("metric", m.name);
                        row.put("unit", m.unit);
                        row.put("dc", host);
                        row.put("a", dcA.getOrDefault(key, Map.of()).get(host));
                        row.put("b", dcB.getOrDefault(key, Map.of()).get(host));
                        dc.add(row);
                    }
                }
            } catch (IOException | RuntimeException e) {
                dcError = "Could not read DC metrics from InfluxDB: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dcError = "Interrupted reading DC metrics";
            }
        }
        out.put("dc", dc);
        out.put("dcError", dcError);
        return out;
    }

    private final class RunData {
        final String id;
        final Properties meta = new Properties();
        final Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
        long firstSampleMs;
        long lastSampleMs;

        RunData(String id) {
            this.id = id;
        }

        /** Steady state: from the end of ramp-up to the last sample. */
        long windowStartMs() {
            long rampup = Long.parseLong(meta.getProperty("rampup", "0"));
            long start = firstSampleMs + rampup * 1000;
            return start < lastSampleMs ? start : firstSampleMs;
        }

        Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            meta.stringPropertyNames().stream().sorted().forEach(k -> m.put(k, meta.getProperty(k)));
            m.put("firstSampleMs", firstSampleMs);
            m.put("lastSampleMs", lastSampleMs);
            m.put("windowStartMs", windowStartMs());
            return m;
        }
    }

    private RunData load(String id) throws IOException {
        if (id == null || !RUN_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid run id");
        }
        Path dir = resultsDir.resolve(id);
        Path statistics = dir.resolve("report/statistics.json");
        if (!Files.isRegularFile(statistics)) {
            throw new IllegalArgumentException("Run " + id + " has no report to compare");
        }
        RunData run = new RunData(id);
        Path meta = dir.resolve("run.properties");
        if (Files.exists(meta)) {
            try (Reader r = Files.newBufferedReader(meta)) {
                run.meta.load(r);
            }
        }
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(statistics));
        } catch (IllegalStateException e) {
            throw new IOException("Unreadable statistics.json for run " + id + ": " + e.getMessage(), e);
        }
        if (parsed instanceof Map<?, ?> all) {
            all.forEach((name, value) -> {
                if (value instanceof Map<?, ?> s) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    STATS.forEach((from, to) -> row.put(to, s.get(from)));
                    row.put("errors", s.get("errorCount"));
                    run.stats.put(String.valueOf(name), row);
                }
            });
        }
        sampleWindow(dir.resolve("results.jtl"), run);
        return run;
    }

    /** First and last sample times from the CSV results file (timeStamp and elapsed columns). */
    private static void sampleWindow(Path jtl, RunData run) throws IOException {
        if (!Files.isRegularFile(jtl)) {
            return;
        }
        long first = Long.MAX_VALUE;
        long last = 0;
        try (BufferedReader r = Files.newBufferedReader(jtl, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null || !header.startsWith("timeStamp,elapsed,")) {
                return;
            }
            String line;
            while ((line = r.readLine()) != null) {
                int c1 = line.indexOf(',');
                int c2 = c1 < 0 ? -1 : line.indexOf(',', c1 + 1);
                if (c2 < 0) {
                    continue;
                }
                try {
                    long ts = Long.parseLong(line.substring(0, c1));
                    long elapsed = Long.parseLong(line.substring(c1 + 1, c2));
                    first = Math.min(first, ts);
                    last = Math.max(last, ts + elapsed);
                } catch (NumberFormatException ignored) {
                    // A label containing newlines; skip the continuation line.
                }
            }
        }
        if (last > 0) {
            run.firstSampleMs = first;
            run.lastSampleMs = last;
        }
    }

    /** metric key -> DC -> value over the run's steady state. */
    private Map<String, Map<String, Double>> dcMetrics(RunData run) throws IOException, InterruptedException {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        if (run.lastSampleMs == 0) {
            return out;
        }
        String window = "time >= " + run.windowStartMs() + "ms AND time <= " + run.lastSampleMs + "ms";
        String query = DC_METRICS.stream()
                .map(m -> "SELECT " + m.agg + "(\"" + m.field + "\") FROM \"" + m.measurement + "\" WHERE "
                        + (m.where == null ? "" : m.where + " AND ") + window + " GROUP BY \"" + m.hostTag + "\"")
                .collect(Collectors.joining("; "));
        HttpRequest request = HttpRequest.newBuilder(URI.create(influxUrl + "/query?db=telegraf&epoch=ms&q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("InfluxDB returned " + response.statusCode());
        }
        if (!(Json.parse(response.body()) instanceof Map<?, ?> body) || !(body.get("results") instanceof List<?> results)) {
            return out;
        }
        for (int i = 0; i < results.size() && i < DC_METRICS.size(); i++) {
            Metric m = DC_METRICS.get(i);
            if (!(results.get(i) instanceof Map<?, ?> result) || !(result.get("series") instanceof List<?> series)) {
                continue;
            }
            for (Object s : series) {
                if (s instanceof Map<?, ?> ser && ser.get("values") instanceof List<?> values && !values.isEmpty()
                        && values.get(0) instanceof List<?> row && row.size() > 1 && row.get(1) instanceof Double v) {
                    String host = ser.get("tags") instanceof Map<?, ?> tags ? String.valueOf(tags.get(m.hostTag)) : "DC";
                    out.computeIfAbsent(m.measurement + "/" + m.name, k -> new LinkedHashMap<>()).put(host, v);
                }
            }
        }
        return out;
    }
}
