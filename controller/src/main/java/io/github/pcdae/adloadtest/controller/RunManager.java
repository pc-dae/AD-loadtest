package io.github.pcdae.adloadtest.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs JMeter as a child process, one run at a time (JMeter's shutdown port is per host),
 * and keeps enough state to report progress. Each run's settings and outcome are saved as
 * run.properties next to its results so the history survives restarts.
 *
 * <p>With remote load generators (JMeter servers, {@code REMOTE_HOSTS}) the child process is the
 * JMeter client: it sends the plan and properties to the first {@code generators} servers, collects
 * their samples into one results file and report, and the console summary covers all of them.
 */
final class RunManager {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern SUMMARY = Pattern.compile(
            "summary ([=+])\\s+(\\d+) in (\\S+) =\\s+([\\d.]+)/s Avg:\\s+(\\d+) Min:\\s+(\\d+) Max:\\s+(\\d+) Err:\\s+(\\d+) \\(([\\d.]+)%\\)(?: Active: (\\d+))?");
    private static final int LOG_LINES = 200;
    /** Fixed so the callback ports from the generators are predictable (this and the next few). */
    private static final int CLIENT_RMI_PORT = 60000;

    private final Path configDir;
    private final Path resultsDir;
    private final Path jmeterHome;
    private final String plan;
    private final String jvmArgs;
    private final List<String> remoteHosts;

    private volatile Run current;

    /**
     * @param remoteHosts JMeter servers to use as load generators, empty to run the load in the child process
     * @param rmiHostname address the servers call back on with results, when {@code remoteHosts} is set
     */
    RunManager(Path configDir, Path resultsDir, Path jmeterHome, String plan, String krb5Conf,
               List<String> remoteHosts, String rmiHostname) {
        this.configDir = configDir;
        this.resultsDir = resultsDir;
        this.jmeterHome = jmeterHome;
        this.plan = plan;
        this.remoteHosts = List.copyOf(remoteHosts);
        this.jvmArgs = "-Djava.security.krb5.conf=" + krb5Conf + " -Djavax.security.auth.useSubjectCredsOnly=true"
                + (rmiHostname.isEmpty() ? "" : " -Djava.rmi.server.hostname=" + rmiHostname);
    }

    List<String> remoteHosts() {
        return remoteHosts;
    }

    final class Run {
        final String id;
        final RunRequest request;
        final Instant started = Instant.now();
        final Path dir;
        final Deque<String> log = new ArrayDeque<>();
        volatile Process process;
        volatile Instant ended;
        volatile Integer exitCode;
        volatile boolean stopRequested;
        volatile Map<String, Object> total;
        volatile Map<String, Object> interval;

        Run(String id, RunRequest request) {
            this.id = id;
            this.request = request;
            this.dir = resultsDir.resolve(id);
        }

        boolean running() {
            return ended == null;
        }

        synchronized void addLog(String line) {
            log.addLast(line);
            while (log.size() > LOG_LINES) {
                log.removeFirst();
            }
        }

        synchronized List<String> tail(int n) {
            List<String> all = new ArrayList<>(log);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.putAll(request.toMap());
            m.put("startedAt", started.toString());
            m.put("endedAt", ended == null ? null : ended.toString());
            m.put("elapsedSec", Duration.between(started, ended == null ? Instant.now() : ended).toSeconds());
            m.put("exitCode", exitCode);
            m.put("stopRequested", stopRequested);
            m.put("total", total);
            m.put("interval", interval);
            m.put("hasReport", Files.exists(dir.resolve("report/index.html")));
            return m;
        }
    }

    List<String> profiles() throws IOException {
        try (Stream<Path> files = Files.list(configDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> RunRequest.PROFILE_NAME.matcher(n).matches())
                    .sorted()
                    .toList();
        }
    }

    synchronized Run start(RunRequest request) throws IOException {
        if (current != null && current.running()) {
            throw new IllegalStateException("Run " + current.id + " is still in progress");
        }
        if (!profiles().contains(request.profile())) {
            throw new IllegalArgumentException("Unknown profile " + request.profile());
        }
        String id = RUN_ID.format(Instant.now());
        Run run = new Run(id, request);
        Files.createDirectories(run.dir);

        String profile = configDir.resolve(request.profile()).toString();
        List<String> cmd = new ArrayList<>(List.of(
                jmeterHome.resolve("bin/jmeter").toString(), "-n",
                "-t", configDir.resolve(plan).toString(),
                "-q", profile));
        Map<String, String> props = new LinkedHashMap<>(request.jmeterProperties());
        props.put("run.title", request.label().isEmpty() ? "Run " + id : "Run " + id + " " + request.label());
        props.forEach((k, v) -> cmd.add("-J" + k + "=" + v));
        if (request.generators() > 0) {
            // Servers only get what is sent with -G: the profile file, then this run's overrides.
            cmd.add("-R" + String.join(",", remoteHosts.subList(0, request.generators())));
            cmd.add("-G" + profile);
            props.forEach((k, v) -> cmd.add("-G" + k + "=" + v));
            cmd.add("-Jserver.rmi.ssl.disable=true");
            cmd.add("-Jclient.rmi.localport=" + CLIENT_RMI_PORT);
        }
        // Console summaries every 5 s drive the live tiles in the UI.
        cmd.add("-Jsummariser.interval=5");
        cmd.addAll(List.of(
                "-l", run.dir.resolve("results.jtl").toString(),
                "-j", run.dir.resolve("jmeter.log").toString(),
                "-e", "-o", run.dir.resolve("report").toString()));

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).directory(run.dir.toFile());
        pb.environment().put("JVM_ARGS", jvmArgs);
        saveMetadata(run);
        run.addLog("$ " + String.join(" ", cmd));
        run.process = pb.start();
        current = run;
        Thread.ofVirtual().name("jmeter-" + id).start(() -> pump(run));
        return run;
    }

    private void pump(Run run) {
        try (BufferedReader out = new BufferedReader(new InputStreamReader(run.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                run.addLog(line);
                Matcher m = SUMMARY.matcher(line);
                if (m.find()) {
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("samples", Long.parseLong(m.group(2)));
                    stats.put("window", m.group(3));
                    stats.put("throughput", Double.parseDouble(m.group(4)));
                    stats.put("avgMs", Long.parseLong(m.group(5)));
                    stats.put("minMs", Long.parseLong(m.group(6)));
                    stats.put("maxMs", Long.parseLong(m.group(7)));
                    stats.put("errors", Long.parseLong(m.group(8)));
                    stats.put("errorPct", Double.parseDouble(m.group(9)));
                    if (m.group(10) != null) {
                        stats.put("active", Integer.parseInt(m.group(10)));
                    }
                    if (m.group(1).equals("=")) {
                        run.total = stats;
                    } else {
                        run.interval = stats;
                    }
                }
            }
            run.exitCode = run.process.waitFor();
        } catch (IOException e) {
            run.addLog("controller: lost JMeter output: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            run.ended = Instant.now();
            saveMetadata(run);
        }
    }

    /** Asks JMeter to finish gracefully; kills it if it hasn't exited within a minute. */
    synchronized boolean stop() {
        Run run = current;
        if (run == null || !run.running()) {
            return false;
        }
        run.stopRequested = true;
        run.addLog("controller: stop requested");
        try {
            new ProcessBuilder(jmeterHome.resolve("bin/shutdown.sh").toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            run.addLog("controller: shutdown.sh failed (" + e.getMessage() + "), terminating JMeter");
            run.process.destroy();
        }
        Thread.ofVirtual().start(() -> {
            try {
                if (!run.process.waitFor(60, TimeUnit.SECONDS)) {
                    run.addLog("controller: JMeter did not stop in 60 s, killing it");
                    run.process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return true;
    }

    Run current() {
        return current;
    }

    /** Every run directory, newest first, including ones started from the command line. */
    List<Map<String, Object>> history() throws IOException {
        if (!Files.isDirectory(resultsDir)) {
            return List.of();
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(resultsDir)) {
            List<Path> sorted = dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(RunManager::createdAt).reversed())
                    .toList();
            for (Path dir : sorted) {
                Run live = current;
                if (live != null && live.id.equals(dir.getFileName().toString())) {
                    Map<String, Object> m = live.toMap();
                    m.put("source", "web");
                    runs.add(m);
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", dir.getFileName().toString());
                Path meta = dir.resolve("run.properties");
                if (Files.exists(meta)) {
                    Properties p = new Properties();
                    try (Reader r = Files.newBufferedReader(meta)) {
                        p.load(r);
                    }
                    p.stringPropertyNames().stream().sorted().forEach(k -> m.put(k, p.getProperty(k)));
                    m.put("source", "web");
                } else {
                    m.put("source", "cli");
                }
                m.put("hasReport", Files.exists(dir.resolve("report/index.html")));
                runs.add(m);
            }
        }
        return runs;
    }

    /** CLI and web runs use different naming, so order by when the directory appeared. */
    private static FileTime createdAt(Path dir) {
        try {
            return Files.readAttributes(dir, BasicFileAttributes.class).creationTime();
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private void saveMetadata(Run run) {
        Properties p = new Properties();
        run.request.store(p);
        p.setProperty("startedAt", run.started.toString());
        if (run.ended != null) {
            p.setProperty("endedAt", run.ended.toString());
            p.setProperty("exitCode", String.valueOf(run.exitCode));
            p.setProperty("stopRequested", Boolean.toString(run.stopRequested));
        }
        Map<String, Object> total = run.total;
        if (total != null) {
            total.forEach((k, v) -> p.setProperty("total." + k, String.valueOf(v)));
        }
        try (Writer w = Files.newBufferedWriter(run.dir.resolve("run.properties"))) {
            p.store(w, "AD load test run " + run.id);
        } catch (IOException e) {
            run.addLog("controller: could not save run.properties: " + e.getMessage());
        }
    }
}
