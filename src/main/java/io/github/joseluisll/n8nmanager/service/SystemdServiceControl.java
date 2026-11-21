package io.github.joseluisll.n8nmanager.service;

import io.github.joseluisll.n8nmanager.config.ServiceProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * systemd user-session implementation of {@link ServiceControlPort} (US-05.2).
 *
 * <p>Every action is idempotent (NFR-1): start/stop/enable/disable first probe
 * the current state and report an already-in-state no-op instead of blindly
 * invoking systemctl, and a mutation is always followed by a state read-back
 * so the reported outcome reflects the unit's real state, not just systemctl's
 * exit code. Failures surface systemctl's stderr plus a journal tail (FR-2).
 *
 * <p>A missing systemd user session fails with an actionable diagnostic
 * naming the {@code XDG_RUNTIME_DIR} fix (R-1) — never a raw dbus error.
 */
@Service
public class SystemdServiceControl implements ServiceControlPort {

    /** Runner seam so tests can stub systemctl/journalctl execution. */
    @FunctionalInterface
    public interface CommandRunner {
        Result run(String... command) throws Exception;
    }

    public record Result(int exitCode, String stdout, String stderr) {
    }

    private final ServiceProperties properties;
    private final CommandRunner runner;

    @org.springframework.beans.factory.annotation.Autowired
    public SystemdServiceControl(ServiceProperties properties) {
        this(properties, SystemdServiceControl::execute);
    }

    /** Test constructor: inject a stubbed runner. */
    SystemdServiceControl(ServiceProperties properties, CommandRunner runner) {
        this.properties = properties;
        this.runner = runner;
    }

    @Override
    public ServiceResult start() {
        String state = queryActiveState();
        if ("active".equals(state)) {
            return new ServiceResult("start", false, true, unit() + " is already active");
        }
        return mutate("start", "started", "active");
    }

    @Override
    public ServiceResult stop() {
        String state = queryActiveState();
        if (!"active".equals(state)) {
            return new ServiceResult("stop", false, true,
                    unit() + " is already not active (state: " + state + ")");
        }
        return mutate("stop", "stopped", "inactive");
    }

    @Override
    public ServiceResult restart() {
        // Restart is meaningful from any state (an inactive unit is started).
        return mutate("restart", "restarted", "active");
    }

    @Override
    public ServiceResult enable() {
        return mutateEnablement("enable", "enabled");
    }

    @Override
    public ServiceResult disable() {
        return mutateEnablement("disable", "disabled");
    }

    @Override
    public ServiceStatus status() {
        String activeState;
        try {
            activeState = queryActiveState();
        } catch (IllegalStateException e) {
            // The session itself is unusable (R-1): report it as the status diagnostic.
            return new ServiceStatus(unit(), null, null, null, null, null, null, List.of(), e.getMessage());
        }
        Map<String, String> props = readProperties();
        return new ServiceStatus(
                unit(),
                orDefault(props.get("ActiveState"), activeState),
                props.get("SubState"),
                props.get("MainPID"),
                props.get("ActiveEnterTimestamp"),
                props.get("UnitFileState"),
                props.get("FragmentPath"),
                recentLogs(),
                null);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String unit() {
        return properties.unit();
    }

    private String queryActiveState() {
        try {
            Result result = runner.run("systemctl", "--user", "is-active", unit());
            return firstLine(result.stdout()) != null ? firstLine(result.stdout()).trim() : "unknown";
        } catch (Exception e) {
            throw sessionFailure("could not query the unit state", e);
        }
    }

    /** Runs a state-mutating systemctl verb and verifies the outcome with a read-back against the expected post-state. */
    private ServiceResult mutate(String verb, String pastTense, String expectedState) {
        try {
            Result result = runner.run("systemctl", "--user", verb, unit());
            if (result.exitCode() != 0) {
                return new ServiceResult(verb, false, false,
                        unit() + " " + verb + " failed (exit " + result.exitCode() + "): "
                                + firstLine(result.stderr()) + journalHint());
            }
        } catch (Exception e) {
            throw sessionFailure(verb + " could not be invoked", e);
        }
        String state = queryActiveState();
        if (!expectedState.equals(state)) {
            return new ServiceResult(verb, false, false,
                    unit() + " reported state '" + state + "' after " + verb
                            + " (expected " + expectedState + ")" + journalHint());
        }
        return new ServiceResult(verb, true, true, unit() + " " + pastTense);
    }

    private ServiceResult mutateEnablement(String verb, String pastTense) {
        String targetState = "enable".equals(verb) ? "enabled" : "disabled";
        String current;
        try {
            Result probe = runner.run("systemctl", "--user", "is-enabled", unit());
            current = firstLine(probe.stdout()) != null ? firstLine(probe.stdout()).trim() : "unknown";
            if (targetState.equals(current)) {
                return new ServiceResult(verb, false, true, unit() + " is already " + current);
            }
        } catch (Exception e) {
            throw sessionFailure("could not query the unit's enablement", e);
        }
        try {
            Result result = runner.run("systemctl", "--user", verb, unit());
            if (result.exitCode() != 0) {
                return new ServiceResult(verb, false, false,
                        unit() + " " + verb + " failed (exit " + result.exitCode() + "): "
                                + firstLine(result.stderr()));
            }
        } catch (Exception e) {
            throw sessionFailure(verb + " could not be invoked", e);
        }
        return new ServiceResult(verb, true, true, unit() + " " + pastTense + " (was " + current + ")");
    }

    private Map<String, String> readProperties() {
        try {
            Result result = runner.run("systemctl", "--user", "show", unit(),
                    "--property=ActiveState,SubState,MainPID,ActiveEnterTimestamp,UnitFileState,FragmentPath");
            Map<String, String> props = new LinkedHashMap<>();
            for (String line : result.stdout().split("\\R")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    props.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            return props;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Best-effort journal tail; a journalctl failure is tolerated (logged state only). */
    private List<String> recentLogs() {
        try {
            Result result = runner.run("journalctl", "--user", "-u", unit(),
                    "-n", String.valueOf(properties.journalLines()), "--no-pager");
            if (result.exitCode() != 0) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (String line : result.stdout().split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Renders the operator-facing status report (FR-2). */
    public String render(ServiceStatus s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Unit:        ").append(s.unit()).append('\n');
        sb.append("Active:      ").append(s.activeState() == null ? "unknown" : s.activeState());
        if (s.subState() != null && !s.subState().isBlank()) {
            sb.append(" (").append(s.subState()).append(')');
        }
        if (s.since() != null && !s.since().isBlank()) {
            sb.append(" since ").append(s.since());
        }
        sb.append('\n');
        sb.append("Main PID:    ").append(s.mainPid() == null || s.mainPid().isBlank() ? "-" : s.mainPid()).append('\n');
        sb.append("Enabled:     ").append(s.enabledState() == null ? "unknown" : s.enabledState()).append('\n');
        sb.append("Unit file:   ").append(s.fragmentPath() == null || s.fragmentPath().isBlank() ? "-" : s.fragmentPath()).append('\n');
        if (s.recentLogs() != null && !s.recentLogs().isEmpty()) {
            sb.append("Recent logs (last ").append(s.recentLogs().size()).append("):\n");
            for (String line : s.recentLogs()) {
                sb.append("  ").append(line).append('\n');
            }
        }
        if (s.diagnostic() != null) {
            sb.append("Error:       ").append(s.diagnostic()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String journalHint() {
        return " - recent logs: journalctl --user -u " + unit() + " -n 20 --no-pager";
    }

    private IllegalStateException sessionFailure(String what, Exception cause) {
        String fix = "export XDG_RUNTIME_DIR=/run/user/$(id -u) (run from a login session; "
                + "the systemd user manager must exist, 'loginctl enable-linger " + System.getProperty("user.name") + "' keeps it alive)";
        return new IllegalStateException(what + ": no usable systemd user session ("
                + cause.getMessage() + "). Fix: " + fix, cause);
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

    /** Real runner: process execution with a bounded wait and drained streams. */
    private static Result execute(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tout = drain(process.getInputStream(), out);
        Thread terr = drain(process.getErrorStream(), err);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(command[0] + " timed out");
        }
        tout.join(5000);
        terr.join(5000);
        return new Result(process.exitValue(), out.toString(), err.toString());
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
}
