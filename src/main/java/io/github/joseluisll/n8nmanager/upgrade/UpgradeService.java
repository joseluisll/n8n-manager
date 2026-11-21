package io.github.joseluisll.n8nmanager.upgrade;

import io.github.joseluisll.n8nmanager.config.DependencyProperties;
import io.github.joseluisll.n8nmanager.config.UpgradeProperties;
import io.github.joseluisll.n8nmanager.deps.DependencyChecker;
import io.github.joseluisll.n8nmanager.service.ServiceControlPort;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Upgrade flow for the local n8n Community instance (US-05.3), decisions
 * D-1/D-2 (owner-approved 2026-11-21):
 *
 * <ul>
 *   <li>the target version defaults to the SINGLE-SOURCE pin
 *       ({@code n8n.deps.expected.n8n} in application.yml); an explicit
 *       {@code --version} overrides it for one run and the resulting pin
 *       drift is REPORTED (deps status flags it afterwards);</li>
 *   <li>the upgrade ALWAYS stops the service first (never hot-installs —
 *       the US-05.1 evidence shows plain npm re-resolution churns the tree
 *       under the live service), backs up unit + env before touching
 *       anything, and health-gates the restart;</li>
 *   <li>Community-only guard (FR-6): any {@code N8N_LICENSE*} line in the
 *       env file REFUSES the upgrade before anything runs.</li>
 * </ul>
 */
@Service
public class UpgradeService {

    /** Runner seam for npm/n8n execution (tests stub it). */
    @FunctionalInterface
    public interface CommandRunner {
        SystemdServiceControlResult run(String... command) throws Exception;
    }

    /** Kept in sync with NPM_ALLOW_SCRIPTS in docs/install/install-n8n.sh. */
    static final String NPM_ALLOW_SCRIPTS =
            "@parcel/watcher,isolated-vm,sqlite3,protobufjs,agent-browser,oracledb,"
                    + "msgpackr-extract,ssh2,@sentry/node-native-stacktrace,"
                    + "@sentry/node-cpu-profiler,@confluentinc/kafka-javascript,"
                    + "cpu-features,@scarf/scarf,vue-demi";

    /** Health-probe seam (tests stub it). */
    @FunctionalInterface
    public interface HealthProbe {
        boolean healthy(String url) throws Exception;
    }

    public record Result(String from, String to, boolean changed, boolean success, String message) {
    }

    public record NodesResult(List<String> updated, boolean success, String message) {
    }

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+([-.][0-9A-Za-z.-]+)?");

    private final DependencyProperties dependencyProperties;
    private final UpgradeProperties properties;
    private final ServiceControlPort service;
    private final DependencyChecker dependencyChecker;
    private final CommandRunner runner;
    private final HealthProbe healthProbe;

    @org.springframework.beans.factory.annotation.Autowired
    public UpgradeService(DependencyProperties dependencyProperties, UpgradeProperties properties,
                          ServiceControlPort service, DependencyChecker dependencyChecker) {
        this(dependencyProperties, properties, service, dependencyChecker,
                UpgradeService::execute, url -> realHealthProbe(url));
    }

    /** Test constructor: stubbed runner + health probe. */
    UpgradeService(DependencyProperties dependencyProperties, UpgradeProperties properties,
                   ServiceControlPort service, DependencyChecker dependencyChecker,
                   CommandRunner runner, HealthProbe healthProbe) {
        this.dependencyProperties = dependencyProperties;
        this.properties = properties;
        this.service = service;
        this.dependencyChecker = dependencyChecker;
        this.runner = runner;
        this.healthProbe = healthProbe;
    }

    /** Default target: the single-source pin; override for one run with an explicit version. */
    public String pin() {
        String expected = dependencyProperties != null && dependencyProperties.expected() != null
                ? dependencyProperties.expected().n8n() : null;
        return expected != null && !expected.isBlank() ? expected : "2.48.7";
    }

    public Result upgrade(String requestedVersion) {
        String target = requestedVersion != null && !requestedVersion.isBlank()
                ? requestedVersion : pin();
        if (!VERSION.matcher(target).matches()) {
            return new Result(null, target, false, false,
                    "'" + target + "' is not a valid n8n version (expected e.g. 2.48.7)");
        }

        // FR-6: Community-only guard — an enterprise license line REFUSES the
        // upgrade before anything runs.
        String licenseLine = findLicenseLine();
        if (licenseLine != null) {
            return new Result(null, target, false, false,
                    "REFUSED: " + properties.envFile() + " contains '" + licenseLine
                            + "' — this system manages the Community edition only (owner decision,"
                            + " issue #18); remove the enterprise license line before upgrading");
        }

        String from = probeVersion();
        if (from != null && from.equals(target)) {
            return new Result(from, target, false, true,
                    "n8n is already at " + target + " — nothing to do (pin: " + pin() + ")");
        }

        // Pre-checks: the runtime deps must be satisfied before touching n8n.
        for (String dep : List.of("node", "npm")) {
            DependencyChecker.DependencyStatus s = dependencyChecker.check(dep);
            if (!s.ok()) {
                return new Result(from, target, false, false,
                        "REFUSED: prerequisite " + dep + " not satisfied (" + s.diagnostic() + ")");
            }
        }

        BackupOutcome backup = backupFiles();
        if (!backup.failed().isEmpty()) {
            // Safety-first: an upgrade without its backups must not run (the
            // files were not modified — the abort happens before the stop).
            return new Result(from, target, false, false,
                    "REFUSED: backing up " + String.join(", ", backup.failed())
                            + " failed - aborting before touching anything; check the backup dir "
                            + properties.backupDir() + " and re-run");
        }
        String backupNote = backup.backedUp().isEmpty() ? "no unit/env files found to back up"
                : "backed up " + String.join(", ", backup.backedUp()) + " -> " + properties.backupDir();

        // D-2: always stop before installing — never hot-install under the live service.
        ServiceControlPort.ServiceResult stop = service.stop();
        if (!stop.success()) {
            // The install must not run under a possibly-live service (D-2).
            return new Result(from, target, false, false,
                    "could not stop the service before installing (D-2 forbids a hot install): "
                            + stop.message() + ". Nothing was modified.");
        }

        SystemdServiceControlResult install;
        try {
            install = runner.run("npm", "install", "-g",
                    "--allow-scripts=" + NPM_ALLOW_SCRIPTS, "n8n@" + target);
        } catch (Exception e) {
            String restore = restoreService();
            return new Result(from, target, false, false,
                    "npm install failed (" + e.getMessage() + "). " + backupNote
                            + ". " + restore + ". Rollback: n8n upgrade --version "
                            + (from != null ? from : pin()));
        }
        if (install.exitCode() != 0) {
            String restore = restoreService();
            return new Result(from, target, false, false,
                    "npm install n8n@" + target + " failed (exit " + install.exitCode() + "): "
                            + firstLine(install.stderr()) + ". " + backupNote + ". " + restore
                            + ". Rollback: n8n upgrade --version "
                            + (from != null ? from : pin()));
        }

        String installed = probeVersion();
        if (!target.equals(installed)) {
            String restore = restoreService();
            String foundDesc = installed == null
                    ? "the version read-back FAILED (n8n binary missing or broken after install)"
                    : "the n8n binary reports '" + installed + "'";
            return new Result(from, installed, false, false,
                    "after npm install " + foundDesc + ", expected '" + target
                            + "'. " + backupNote + ". " + restore
                            + ". Rollback: n8n upgrade --version " + (from != null ? from : pin()));
        }

        ServiceControlPort.ServiceResult start = service.start();
        if (!start.success()) {
            return new Result(from, installed, false, false,
                    "installed " + target + " but the service did not come back: " + start.message()
                            + ". " + backupNote + ". Rollback: n8n upgrade --version "
                            + (from != null ? from : pin()));
        }
        if (!awaitHealthy()) {
            return new Result(from, installed, false, false,
                    "installed " + target + " and the service is active, but " + properties.healthUrl()
                            + " did not answer ok within " + properties.healthTimeoutSeconds()
                            + "s. " + backupNote + ". Rollback: n8n upgrade --version "
                            + (from != null ? from : pin()));
        }

        String drift = target.equals(pin()) ? ""
                : " NOTE: '" + target + "' differs from the configured pin (" + pin()
                + ") - update n8n.deps.expected.n8n in application.yml or the next upgrade will revert it (deps status flags this).";
        return new Result(from, installed, true, true,
                "n8n upgraded " + (from != null ? from : "?") + " -> " + installed + ". "
                        + backupNote + ". Health OK." + drift);
    }

    /**
     * Community-node (plugin) upgrade path: updates every dependency of the
     * n8n community-nodes package dir, then restarts + health-gates. A
     * missing/empty nodes dir is a no-op success (there is nothing to
     * upgrade).
     */
    public NodesResult upgradeNodes() {
        Path nodesDir = Path.of(System.getProperty("user.home"), ".n8n", "nodes");
        Path packageJson = nodesDir.resolve("package.json");
        List<String> deps = readDependencies(packageJson);
        if (deps.isEmpty()) {
            return new NodesResult(List.of(), true,
                    "no community nodes installed in " + nodesDir + " — nothing to upgrade");
        }
        for (String dep : deps) {
            SystemdServiceControlResult r;
            try {
                r = runner.run("npm", "install", "--prefix", nodesDir.toString(), dep + "@latest");
            } catch (Exception e) {
                return new NodesResult(List.of(), false,
                        "npm install " + dep + "@latest failed (" + e.getMessage() + ")");
            }
            if (r.exitCode() != 0) {
                return new NodesResult(List.of(), false,
                        "npm install " + dep + "@latest failed (exit " + r.exitCode() + "): "
                                + firstLine(r.stderr()));
            }
        }
        ServiceControlPort.ServiceResult restart = service.restart();
        if (!restart.success()) {
            return new NodesResult(deps, false, "updated " + deps + " but the service did not restart: "
                    + restart.message());
        }
        if (!awaitHealthy()) {
            return new NodesResult(deps, false, "updated " + deps + " but " + properties.healthUrl()
                    + " did not answer ok within " + properties.healthTimeoutSeconds() + "s");
        }
        return new NodesResult(deps, true, "community nodes updated and instance healthy: " + deps);
    }

    private List<String> readDependencies(Path packageJson) {
        if (packageJson == null || !Files.isRegularFile(packageJson)) {
            return List.of();
        }
        try {
            // Jackson ships with spring-boot-starter-web — no hand-rolled
            // JSON parsing (a brace inside a string value truncated the list
            // silently with the previous regex approach).
            com.fasterxml.jackson.databind.JsonNode deps = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(packageJson.toFile()).get("dependencies");
            if (deps == null || !deps.isObject()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            deps.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (IOException e) {
            return List.of();
        }
    }

    private String findLicenseLine() {
        Path env = Path.of(properties.envFile());
        if (!Files.isRegularFile(env)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.isBlank()) {
                    continue;
                }
                // Tolerate an 'export ' prefix and any letter case (finding 6).
                String key = trimmed.startsWith("export ") ? trimmed.substring(7).trim() : trimmed;
                if (key.toUpperCase(java.util.Locale.ROOT).startsWith("N8N_LICENSE")) {
                    return key.split("=")[0].trim();
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private String probeVersion() {
        try {
            SystemdServiceControlResult r = runner.run("n8n", "--version");
            if (r.exitCode() != 0) {
                return null;
            }
            String line = firstLine(r.stdout());
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Backup result: which files were copied and which EXISTED but failed to copy. */
    record BackupOutcome(List<String> backedUp, List<String> failed) {
    }

    private BackupOutcome backupFiles() {
        List<String> backed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = Path.of(properties.backupDir(), stamp);
        for (String file : List.of(properties.unitFile(), properties.envFile())) {
            Path src = Path.of(file);
            if (Files.isRegularFile(src)) {
                try {
                    Files.createDirectories(dir);
                    Files.copy(src, dir.resolve(src.getFileName().toString()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    backed.add(src.getFileName().toString());
                } catch (IOException e) {
                    // A failed backup must be VISIBLE (it aborts the upgrade).
                    failed.add(src.getFileName().toString() + " (" + e.getMessage() + ")");
                }
            }
        }
        return new BackupOutcome(List.copyOf(backed), List.copyOf(failed));
    }

    /** Best-effort: bring the service back after a failed mid-flight upgrade; the outcome is REPORTED, never swallowed. */
    private String restoreService() {
        try {
            ServiceControlPort.ServiceResult start = service.start();
            return start.success()
                    ? "service restore attempt: " + start.message()
                    : "service restore attempt FAILED: " + start.message();
        } catch (Exception e) {
            return "service restore attempt FAILED: " + e.getMessage();
        }
    }

    private boolean awaitHealthy() {
        long deadline = System.currentTimeMillis() + properties.healthTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (healthProbe.healthy(properties.healthUrl())) {
                    return true;
                }
            } catch (Exception ignored) {
                // retry until the deadline
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    private static boolean realHealthProbe(String url) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpResponse<String> response = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(5)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static SystemdServiceControlResult execute(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tout = drain(process.getInputStream(), out);
        Thread terr = drain(process.getErrorStream(), err);
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(command[0] + " timed out");
        }
        tout.join(5000);
        terr.join(5000);
        return new SystemdServiceControlResult(process.exitValue(), out.toString(), err.toString());
    }

    private static Thread drain(java.io.InputStream stream, StringBuilder target) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // stream ended
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Local record name to avoid clashing with DependencyChecker.Result's arity. */
    public record SystemdServiceControlResult(int exitCode, String stdout, String stderr) {
    }
}
