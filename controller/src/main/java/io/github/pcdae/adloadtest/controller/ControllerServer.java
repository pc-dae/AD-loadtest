package io.github.pcdae.adloadtest.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Web front end for driving load tests: a static single-page UI plus a small JSON API.
 *
 * <pre>
 * GET  /api/config         Grafana location for the embedded dashboard
 * GET  /api/profiles       target property files available in the config directory
 * GET  /api/status         current or last run, with live JMeter summary and console tail
 * GET  /api/runs           run history (web and command-line runs)
 * GET  /api/compare?a=&amp;b=  two runs side by side: per-transaction statistics and DC resource use
 * POST /api/runs           start a run (form-encoded RunRequest fields)
 * POST /api/runs/stop      stop the current run gracefully
 * GET  /results/{id}/...   JMeter HTML reports and raw results
 * </pre>
 *
 * There is no authentication: the port is published on loopback only.
 */
public final class ControllerServer {

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("log", "text/plain; charset=utf-8"),
            Map.entry("jtl", "text/csv; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"),
            Map.entry("properties", "text/plain; charset=utf-8"));

    private final RunManager runs;
    private final Comparison comparison;
    private final Path resultsDir;
    private final String grafanaUrl;

    private ControllerServer(RunManager runs, Comparison comparison, Path resultsDir, String grafanaUrl) {
        this.runs = runs;
        this.comparison = comparison;
        this.resultsDir = resultsDir.toAbsolutePath().normalize();
        this.grafanaUrl = grafanaUrl;
    }

    public static void main(String[] args) throws IOException {
        Path config = Path.of(env("CONFIG_DIR", "/config"));
        Path results = Path.of(env("RESULTS_DIR", "/results"));
        List<String> remoteHosts = Arrays.stream(env("REMOTE_HOSTS", "").split(","))
                .map(String::trim).filter(h -> !h.isEmpty()).toList();
        RunManager manager = new RunManager(config, results,
                Path.of(env("JMETER_HOME", "/opt/apache-jmeter")),
                env("PLAN", "ad-auth.jmx"),
                env("KRB5_CONF", config.resolve("krb5.conf").toString()),
                remoteHosts, env("RMI_HOSTNAME", ""));
        ControllerServer app = new ControllerServer(manager, new Comparison(results, env("INFLUX_URL", "")), results,
                env("GRAFANA_URL", "http://localhost:3000"));

        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/", app::api);
        server.createContext("/results/", app::results);
        server.createContext("/", app::staticFile);
        server.start();
        System.out.println("AD load test controller listening on :" + port);
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    private void api(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            try {
                switch (method + " " + path) {
                    case "GET /api/config" -> json(ex, 200, Map.of("grafanaUrl", grafanaUrl, "dashboardUid", "ad-loadtest",
                            "generators", runs.remoteHosts()));
                    case "GET /api/profiles" -> json(ex, 200, runs.profiles());
                    case "GET /api/status" -> json(ex, 200, status());
                    case "GET /api/runs" -> json(ex, 200, runs.history());
                    case "GET /api/compare" -> {
                        Map<String, String> q = query(ex);
                        json(ex, 200, comparison.compare(q.get("a"), q.get("b")));
                    }
                    case "POST /api/runs" -> {
                        RunRequest request = RunRequest.fromForm(form(ex), runs.remoteHosts().size());
                        json(ex, 201, runs.start(request).toMap());
                    }
                    case "POST /api/runs/stop" -> {
                        if (runs.stop()) {
                            json(ex, 202, Map.of("stopping", true));
                        } else {
                            json(ex, 409, Map.of("error", "No run in progress"));
                        }
                    }
                    default -> json(ex, 404, Map.of("error", "Not found"));
                }
            } catch (IllegalArgumentException e) {
                json(ex, 400, Map.of("error", e.getMessage()));
            } catch (IllegalStateException e) {
                json(ex, 409, Map.of("error", e.getMessage()));
            } catch (IOException | RuntimeException e) {
                json(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
    }

    private Map<String, Object> status() {
        RunManager.Run run = runs.current();
        Map<String, Object> m = new LinkedHashMap<>();
        if (run == null) {
            m.put("state", "idle");
            return m;
        }
        m.put("state", !run.running() ? "idle" : run.stopRequested ? "stopping" : "running");
        m.put("run", run.toMap());
        m.put("log", run.tail(40));
        return m;
    }

    /** Serves files under the results directory, refusing anything that escapes it. */
    private void results(HttpExchange ex) throws IOException {
        try (ex) {
            String rel = URLDecoder.decode(ex.getRequestURI().getPath().substring("/results/".length()), StandardCharsets.UTF_8);
            Path file = resultsDir.resolve(rel).normalize();
            if (!file.startsWith(resultsDir)) {
                text(ex, 403, "Forbidden");
                return;
            }
            if (Files.isDirectory(file)) {
                file = file.resolve("index.html");
            }
            if (!Files.isRegularFile(file)) {
                text(ex, 404, "Not found");
                return;
            }
            send(ex, 200, contentType(file.getFileName().toString()), Files.readAllBytes(file));
        }
    }

    private void staticFile(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            if (path.contains("..")) {
                text(ex, 400, "Bad request");
                return;
            }
            try (InputStream in = ControllerServer.class.getResourceAsStream("/static" + path)) {
                if (in == null) {
                    text(ex, 404, "Not found");
                    return;
                }
                send(ex, 200, contentType(path), in.readAllBytes());
            }
        }
    }

    private static Map<String, String> form(HttpExchange ex) throws IOException {
        return decode(new String(ex.getRequestBody().readNBytes(16_384), StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(HttpExchange ex) {
        String raw = ex.getRequestURI().getRawQuery();
        return decode(raw == null ? "" : raw);
    }

    private static Map<String, String> decode(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            form.put(k, v);
        }
        return form;
    }

    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        return CONTENT_TYPES.getOrDefault(dot < 0 ? "" : name.substring(dot + 1), "application/octet-stream");
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void text(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
