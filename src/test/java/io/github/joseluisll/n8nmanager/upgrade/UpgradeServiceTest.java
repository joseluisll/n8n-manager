package io.github.joseluisll.n8nmanager.upgrade;

import io.github.joseluisll.n8nmanager.config.DependencyProperties;
import io.github.joseluisll.n8nmanager.config.UpgradeProperties;
import io.github.joseluisll.n8nmanager.deps.DependencyChecker;
import io.github.joseluisll.n8nmanager.deps.DependencyChecker.DependencyStatus;
import io.github.joseluisll.n8nmanager.service.ServiceControlPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeServiceTest {

    private static final String PIN = "2.48.7";

    @TempDir
    Path tmp;

    private UpgradeProperties props() {
        return new UpgradeProperties(
                tmp.resolve("n8n.env").toString(),
                tmp.resolve("n8n.service").toString(),
                tmp.resolve("backups").toString(),
                "http://127.0.0.1:5678/healthz",
                1);
    }

    private static DependencyProperties depProps() {
        return new DependencyProperties(
                new DependencyProperties.Expected(">=24.0.0", ">=11.0.0", ">=21", ">=3.9.0", PIN),
                "/usr/lib/jvm/java-21-openjdk-amd64");
    }

    private static DependencyChecker okDeps() {
        DependencyProperties p = depProps();
        return new DependencyChecker(p, cmd -> {
            if ("node".equals(cmd[0])) {
                return new DependencyChecker.Result(0, "v24.22.3\n", "");
            }
            if ("npm".equals(cmd[0])) {
                return new DependencyChecker.Result(0, "11.19.1\n", "");
            }
            return new DependencyChecker.Result(0, "", "");
        });
    }

    /** Records every lifecycle call in order. */
    private static final class FakeService implements ServiceControlPort {
        final List<String> calls = new ArrayList<>();
        boolean stopSucceeds = true;
        boolean startSucceeds = true;

        @Override
        public ServiceResult start() {
            calls.add("start");
            return new ServiceResult("start", true, startSucceeds, startSucceeds ? "started" : "start failed");
        }

        @Override
        public ServiceResult stop() {
            calls.add("stop");
            return new ServiceResult("stop", true, stopSucceeds, stopSucceeds ? "stopped" : "stop failed");
        }

        @Override
        public ServiceResult restart() {
            calls.add("restart");
            return new ServiceResult("restart", true, true, "restarted");
        }

        @Override
        public ServiceResult enable() {
            calls.add("enable");
            return new ServiceResult("enable", false, true, "enabled");
        }

        @Override
        public ServiceResult disable() {
            calls.add("disable");
            return new ServiceResult("disable", false, true, "disabled");
        }

        @Override
        public ServiceStatus status() {
            calls.add("status");
            return new ServiceStatus("n8n.service", "active", "running", "1", "now", "enabled", "x", List.of(), null);
        }
    }

    private UpgradeService service(FakeService svc, java.util.function.Function<String[], UpgradeService.SystemdServiceControlResult> runnerFn,
                                   java.util.function.Predicate<String> health) {
        return new UpgradeService(depProps(), props(), svc, okDeps(),
                cmd -> runnerFn.apply(cmd), url -> health.test(url));
    }

    private static UpgradeService.SystemdServiceControlResult ok(String stdout) {
        return new UpgradeService.SystemdServiceControlResult(0, stdout, "");
    }

    // ---- happy path ----

    @Test
    void happyPathStopsInstallsStartsAndHealthGates() {
        FakeService svc = new FakeService();
        List<String[]> commands = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean npmSawStopBefore = new java.util.concurrent.atomic.AtomicBoolean(false);
        int[] probes = {0};
        UpgradeService s = service(svc, cmd -> {
            commands.add(cmd);
            if ("npm".equals(cmd[0]) && "install".equals(cmd[1])) {
                npmSawStopBefore.set(svc.calls.contains("stop") && !svc.calls.contains("start"));
            }
            if ("n8n".equals(cmd[0])) {
                // pre-probe reports the old version, post-install probe the new one
                probes[0]++;
                return probes[0] == 1 ? ok("2.48.0\n") : ok("2.48.7\n");
            }
            return ok("");
        }, url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertTrue(r.success(), r.message());
        assertEquals("2.48.0", r.from());
        assertEquals("2.48.7", r.to());
        assertTrue(r.changed());
        // D-2: at the moment npm install runs, the service must already be
        // stopped and not yet restarted (single timeline, checked in-flight)
        assertTrue(npmSawStopBefore.get(), "service must be stopped BEFORE npm install; calls=" + svc.calls);
        assertTrue(svc.calls.contains("stop"), "stop must run");
        assertTrue(svc.calls.contains("start"), "service restarted after install");
        // allow-scripts list passed (npm >= 11 pitfall, R-3)
        boolean hasAllowScripts = false;
        for (String[] c : commands) {
            if ("install".equals(c[1])) {
                for (String a : c) {
                    if (a.startsWith("--allow-scripts=") && a.contains("isolated-vm")) {
                        hasAllowScripts = true;
                    }
                }
            }
        }
        assertTrue(hasAllowScripts, "npm >= 11 needs the allow-scripts list");
    }

    // ---- refusals (nothing runs) ----

    @Test
    void enterpriseLicenseLineRefusesBeforeAnythingRuns() throws Exception {
        Files.writeString(tmp.resolve("n8n.env"), "N8N_HOST=127.0.0.1\nN8N_LICENSE_ACTIVATION_KEY=abc\n");
        FakeService svc = new FakeService();
        UpgradeService s = service(svc, cmd -> ok(""), url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertFalse(r.success());
        assertTrue(r.message().contains("REFUSED"));
        assertTrue(r.message().contains("N8N_LICENSE_ACTIVATION_KEY"));
        assertTrue(svc.calls.isEmpty(), "no lifecycle action may run after a refusal");
    }

    @Test
    void unsatisfiedRuntimeDepsRefuse() {
        FakeService svc = new FakeService();
        DependencyChecker failing = new DependencyChecker(depProps(), cmd -> {
            if ("node".equals(cmd[0])) {
                return new DependencyChecker.Result(0, "v22.22.1\n", "");
            }
            throw new java.io.IOException("missing");
        });
        UpgradeService s = new UpgradeService(depProps(), props(), svc, failing,
                cmd -> ok("2.48.0\n"), url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertFalse(r.success());
        assertTrue(r.message().contains("REFUSED"));
        assertTrue(r.message().contains("node"));
        assertTrue(svc.calls.isEmpty());
    }

    @Test
    void invalidVersionStringIsRefused() {
        UpgradeService s = service(new FakeService(), cmd -> ok(""), url -> true);
        UpgradeService.Result r = s.upgrade("latest; rm -rf ~");
        assertFalse(r.success());
        assertTrue(r.message().contains("not a valid n8n version"));
    }

    // ---- no-op ----

    @Test
    void alreadyAtTargetIsANoOp() {
        FakeService svc = new FakeService();
        UpgradeService s = service(svc, cmd -> "n8n".equals(cmd[0]) ? ok("2.48.7\n") : ok(""), url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertTrue(r.success());
        assertFalse(r.changed());
        assertTrue(r.message().contains("already at 2.48.7"));
        assertTrue(svc.calls.isEmpty(), "no stop/install/start when already at target");
    }

    // ---- failure paths ----

    @Test
    void npmFailureTriesToRestoreServiceAndReportsRollback() {
        FakeService svc = new FakeService();
        UpgradeService s = service(svc, cmd -> {
            if ("n8n".equals(cmd[0])) {
                return ok("2.48.0\n");
            }
            if ("npm".equals(cmd[0])) {
                return new UpgradeService.SystemdServiceControlResult(1, "", "EACCES: permission denied\n");
            }
            return ok("");
        }, url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertFalse(r.success());
        assertTrue(r.message().contains("EACCES"));
        assertTrue(r.message().contains("Rollback: n8n upgrade --version 2.48.0"));
        assertTrue(svc.calls.contains("start"), "restore attempt after npm failure");
        assertTrue(r.message().contains("service restore attempt"), "restore outcome is reported, not swallowed");
    }

    @Test
    void stopFailureAbortsBeforeAnythingIsModified() {
        FakeService svc = new FakeService();
        svc.stopSucceeds = false;
        UpgradeService s = service(svc, cmd -> {
            if ("n8n".equals(cmd[0])) {
                return ok("2.48.0\n");
            }
            return ok("");
        }, url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertFalse(r.success());
        assertTrue(r.message().contains("could not stop the service"));
        assertTrue(r.message().contains("D-2"), "names the decision that forbids the hot install");
        assertTrue(r.message().contains("Nothing was modified"));
        assertEquals(List.of("stop"), svc.calls, "no install, no restore-start after a failed stop");
    }

    @Test
    void restoreFailureIsReportedWhenTheServiceCannotComeBack() {
        FakeService svc = new FakeService();
        svc.startSucceeds = false;
        UpgradeService s = service(svc, cmd -> {
            if ("n8n".equals(cmd[0])) {
                return ok("2.48.0\n");
            }
            if ("npm".equals(cmd[0])) {
                return new UpgradeService.SystemdServiceControlResult(1, "", "boom\n");
            }
            return ok("");
        }, url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertFalse(r.success());
        assertTrue(r.message().contains("service restore attempt FAILED"),
                "a failed restore must be visible in the report: " + r.message());
    }

    @Test
    void failedBackupAbortsTheUpgradeBeforeAnythingRuns() throws Exception {
        Files.writeString(tmp.resolve("n8n.env"), "N8N_PORT=5678\n");
        // a FILE where the backup directory would be created -> copy fails
        Files.writeString(tmp.resolve("backups"), "not a directory");
        FakeService svc = new FakeService();
        UpgradeService s = service(svc, cmd -> {
            if ("n8n".equals(cmd[0])) {
                return ok("2.48.0\n");
            }
            return ok("");
        }, url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertFalse(r.success());
        assertTrue(r.message().contains("REFUSED: backing up"));
        assertTrue(svc.calls.isEmpty(), "no lifecycle action runs after a failed backup");
    }

    @Test
    void exportPrefixAndLowercaseLicenseLinesAlsoRefuse() throws Exception {
        Files.writeString(tmp.resolve("n8n.env"), "export n8n_license_activation_key=xyz\n");
        FakeService svc = new FakeService();
        UpgradeService s = service(svc, cmd -> ok("2.48.0\n"), url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertFalse(r.success());
        assertTrue(r.message().contains("REFUSED"));
        assertTrue(r.message().contains("n8n_license_activation_key"));
        assertTrue(svc.calls.isEmpty());
    }

    @Test
    void versionMismatchAfterInstallFailsWithRollback() {
        FakeService svc = new FakeService();
        UpgradeService s = service(svc, cmd -> {
            if ("n8n".equals(cmd[0])) {
                return ok("2.48.0\n"); // probe always reports old version
            }
            return ok("");
        }, url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertFalse(r.success());
        assertTrue(r.message().contains("reports '2.48.0', expected '2.48.7'"));
        assertTrue(r.message().contains("Rollback"));
    }

    @Test
    void healthGateFailureReportsRollbackEvenWhenServiceActive() {
        FakeService svc = new FakeService();
        UpgradeService s = service(svc, cmd -> {
            if ("n8n".equals(cmd[0])) {
                // pre-probe old, post-install new
                return probed ? ok("2.48.7\n") : (probed = true) ? ok("2.48.0\n") : ok("2.48.7\n");
            }
            return ok("");
        }, url -> false);
        UpgradeService.Result r = s.upgrade(null);
        assertFalse(r.success());
        assertTrue(r.message().contains("did not answer ok"));
        assertTrue(r.message().contains("Rollback: n8n upgrade --version 2.48.0"));
    }
    private boolean probed = false;

    // ---- backups ----

    @Test
    void unitAndEnvAreBackedUpBeforeTheUpgrade() throws Exception {
        Files.writeString(tmp.resolve("n8n.env"), "N8N_PORT=5678\n");
        Files.writeString(tmp.resolve("n8n.service"), "[Service]\n");
        FakeService svc = new FakeService();
        UpgradeService s = service(svc, cmd -> {
            if ("n8n".equals(cmd[0])) {
                return probed2 ? ok("2.48.7\n") : (probed2 = true) ? ok("2.48.0\n") : ok("2.48.7\n");
            }
            return ok("");
        }, url -> true);
        UpgradeService.Result r = s.upgrade(null);
        assertTrue(r.success(), r.message());
        assertTrue(r.message().contains("backed up"));
        try (var walk = Files.walk(tmp.resolve("backups"))) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            assertEquals(2, files.size(), "unit + env backed up: " + files);
            assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("n8n.env")));
            assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("n8n.service")));
        }
    }
    private boolean probed2 = false;

    // ---- pin drift report ----

    @Test
    void explicitVersionDriftingFromThePinIsReported() {
        FakeService svc = new FakeService();
        UpgradeService s = service(svc, cmd -> {
            if ("n8n".equals(cmd[0])) {
                return probed3 ? ok("2.49.0\n") : (probed3 = true) ? ok("2.48.0\n") : ok("2.49.0\n");
            }
            return ok("");
        }, url -> true);
        UpgradeService.Result r = s.upgrade("2.49.0");
        assertTrue(r.success(), r.message());
        assertTrue(r.message().contains("differs from the configured pin"));
    }
    private boolean probed3 = false;

    // ---- community nodes ----

    @Test
    void nodesUpgradeIsANoOpWhenNothingInstalled() {
        UpgradeService s = service(new FakeService(), cmd -> ok(""), url -> true);
        UpgradeService.NodesResult r = s.upgradeNodes();
        assertTrue(r.success());
        assertTrue(r.updated().isEmpty());
        assertTrue(r.message().contains("no community nodes installed"));
    }

    @Test
    void nodesUpgradeUpdatesEachDependencyAndHealthGates() throws Exception {
        Path nodesDir = Path.of(System.getProperty("user.home"), ".n8n", "nodes");
        // use a temp HOME instead of touching the real ~/.n8n
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            Files.createDirectories(nodesDirWithTmpHome());
            Files.writeString(nodesDirWithTmpHome().resolve("package.json"),
                    "{\n  \"name\": \"installed-nodes\",\n  \"private\": true,\n  \"dependencies\": {\n    \"n8n-nodes-example\": \"1.0.0\"\n  }\n}");
            FakeService svc = new FakeService();
            List<String[]> commands = new ArrayList<>();
            UpgradeService s = service(svc, cmd -> {
                commands.add(cmd);
                return ok("");
            }, url -> true);
            UpgradeService.NodesResult r = s.upgradeNodes();
            assertTrue(r.success(), r.message());
            assertEquals(List.of("n8n-nodes-example"), r.updated());
            assertTrue(svc.calls.contains("restart"));
            boolean npmRan = commands.stream().anyMatch(c -> "npm".equals(c[0]) && c[1].equals("install"));
            assertTrue(npmRan, "npm install pkg@latest must run");
        } finally {
            System.setProperty("user.home", realHome);
        }
    }

    private Path nodesDirWithTmpHome() {
        return Path.of(System.getProperty("user.home"), ".n8n", "nodes");
    }
}
