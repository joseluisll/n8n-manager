package io.github.joseluisll.n8nmanager.service;

import io.github.joseluisll.n8nmanager.config.ServiceProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemdServiceControlTest {

    private static final String UNIT = "n8n.service";

    private static ServiceProperties props() {
        return new ServiceProperties(UNIT, 5);
    }

    /** Canned runner: maps the first two command tokens to results. */
    private static SystemdServiceControl controlWith(java.util.function.Function<String[], SystemdServiceControl.Result> answers) {
        return new SystemdServiceControl(props(), command -> {
            SystemdServiceControl.Result r = answers.apply(command);
            if (r == null) {
                throw new java.io.IOException("unexpected command: " + String.join(" ", command));
            }
            return r;
        });
    }

    private static SystemdServiceControl.Result active() {
        return new SystemdServiceControl.Result(0, "active\n", "");
    }

    private static SystemdServiceControl.Result inactive() {
        return new SystemdServiceControl.Result(3, "inactive\n", "");
    }

    private static SystemdServiceControl.Result enabled() {
        return new SystemdServiceControl.Result(0, "enabled\n", "");
    }

    private static SystemdServiceControl.Result disabled() {
        return new SystemdServiceControl.Result(1, "disabled\n", "");
    }

    private static SystemdServiceControl.Result ok() {
        return new SystemdServiceControl.Result(0, "", "");
    }

    // ---- start ----

    @Test
    void startIsNoopWhenAlreadyActive() {
        List<String[]> invoked = new ArrayList<>();
        SystemdServiceControl control = controlWith(cmd -> {
            invoked.add(cmd);
            return "is-active".equals(cmd[2]) ? active() : ok();
        });
        ServiceControlPort.ServiceResult result = control.start();
        assertTrue(result.success());
        assertFalse(result.changed(), "already-active start must be a no-op");
        assertTrue(result.message().contains("already active"));
        // only the is-active probe ran — no mutation (NFR-1)
        assertEquals(1, invoked.size());
    }

    @Test
    void startRunsAndVerifiesWhenInactive() {
        SystemdServiceControl control = controlWith(cmd -> {
            if ("is-active".equals(cmd[2])) {
                // probe before start reports inactive, read-back after start reports active
                return started ? active() : inactive();
            }
            if ("start".equals(cmd[2])) {
                started = true;
                return ok();
            }
            return null;
        });
        ServiceControlPort.ServiceResult result = control.start();
        assertTrue(result.success());
        assertTrue(result.changed());
        assertTrue(result.message().contains("started"));
    }
    private boolean started = false;

    @Test
    void startFailureSurfacesStderr() {
        SystemdServiceControl control = controlWith(cmd -> {
            if ("is-active".equals(cmd[2])) {
                return inactive();
            }
            if ("start".equals(cmd[2])) {
                return new SystemdServiceControl.Result(1, "",
                        "Failed to start n8n.service: Node.js version not supported\n");
            }
            return null;
        });
        ServiceControlPort.ServiceResult result = control.start();
        assertFalse(result.success());
        assertTrue(result.message().contains("Node.js version not supported"));
        assertTrue(result.message().contains("journalctl"), "failure names where the logs are");
    }

    // ---- stop ----

    @Test
    void stopIsNoopWhenAlreadyInactive() {
        SystemdServiceControl control = controlWith(cmd ->
                "is-active".equals(cmd[2]) ? inactive() : ok());
        ServiceControlPort.ServiceResult result = control.stop();
        assertTrue(result.success());
        assertFalse(result.changed());
        assertTrue(result.message().contains("already not active"));
    }

    @Test
    void stopStopsActiveUnitAndVerifies() {
        SystemdServiceControl control = controlWith(cmd -> {
            if ("is-active".equals(cmd[2])) {
                return stopped ? inactive() : active();
            }
            if ("stop".equals(cmd[2])) {
                stopped = true;
                return ok();
            }
            return null;
        });
        ServiceControlPort.ServiceResult result = control.stop();
        assertTrue(result.success());
        assertTrue(result.changed());
        assertTrue(result.message().contains("stopped"));
    }
    private boolean stopped = false;

    // ---- restart ----

    @Test
    void restartVerifiesActiveAfterwards() {
        SystemdServiceControl control = controlWith(cmd ->
                "is-active".equals(cmd[2]) ? active() : ok());
        ServiceControlPort.ServiceResult result = control.restart();
        assertTrue(result.success());
        assertTrue(result.changed());
        assertTrue(result.message().contains("restarted"));
    }

    // ---- enable / disable ----

    @Test
    void enableIsNoopWhenAlreadyEnabled() {
        SystemdServiceControl control = controlWith(cmd ->
                "is-enabled".equals(cmd[2]) ? enabled() : ok());
        ServiceControlPort.ServiceResult result = control.enable();
        assertTrue(result.success());
        assertFalse(result.changed());
        assertTrue(result.message().contains("already enabled"));
    }

    @Test
    void disableRunsWhenEnabled() {
        SystemdServiceControl control = controlWith(cmd -> {
            if ("is-enabled".equals(cmd[2])) {
                return disabledFlag ? disabled() : enabled();
            }
            if ("disable".equals(cmd[2])) {
                disabledFlag = true;
                return ok();
            }
            return null;
        });
        ServiceControlPort.ServiceResult result = control.disable();
        assertTrue(result.success());
        assertTrue(result.changed());
        assertTrue(result.message().contains("disabled (was enabled)"));
    }
    private boolean disabledFlag = false;

    @Test
    void enablementMutationFailureSurfacesStderr() {
        SystemdServiceControl control = controlWith(cmd -> {
            if ("is-enabled".equals(cmd[2])) {
                return disabled();
            }
            if ("enable".equals(cmd[2])) {
                return new SystemdServiceControl.Result(1, "",
                        "Failed to enable unit: Access denied\n");
            }
            return null;
        });
        ServiceControlPort.ServiceResult result = control.enable();
        assertFalse(result.success());
        assertTrue(result.message().contains("Access denied"));
    }

    // ---- status ----

    @Test
    void statusParsesUnitPropertiesAndLogs() {
        SystemdServiceControl control = controlWith(cmd -> {
            if ("is-active".equals(cmd[2])) {
                return active();
            }
            if ("show".equals(cmd[2])) {
                return new SystemdServiceControl.Result(0,
                        "ActiveState=active\nSubState=running\nMainPID=1808\n"
                                + "ActiveEnterTimestamp=Tue 2026-11-21 19:52:03 CET\n"
                                + "UnitFileState=enabled\nFragmentPath=/home/jllopez/.config/systemd/user/n8n.service\n",
                        "");
            }
            if ("journalctl".equals(cmd[0])) {
                return new SystemdServiceControl.Result(0,
                        "Nov 21 19:52:03 host systemd[1000]: Started n8n.\n"
                                + "Nov 21 19:52:10 host n8n[1808]: Editor is now accessible via:\n",
                        "");
            }
            return null;
        });
        ServiceControlPort.ServiceStatus status = control.status();
        assertEquals("active", status.activeState());
        assertEquals("running", status.subState());
        assertEquals("1808", status.mainPid());
        assertEquals("enabled", status.enabledState());
        assertEquals(2, status.recentLogs().size());
        String rendered = control.render(status);
        assertTrue(rendered.contains("Main PID:    1808"));
        assertTrue(rendered.contains("Editor is now accessible"));
    }

    @Test
    void statusReportsSessionFailureAsDiagnostic() {
        SystemdServiceControl control = new SystemdServiceControl(props(), cmd -> {
            throw new java.io.IOException("Cannot connect to bus");
        });
        ServiceControlPort.ServiceStatus status = control.status();
        assertEquals("n8n.service", status.unit());
        assertTrue(status.diagnostic().contains("XDG_RUNTIME_DIR"), "R-1: names the fix");
        String rendered = control.render(status);
        assertTrue(rendered.contains("Error:"));
    }

    @Test
    void journalFailureIsToleratedInStatus() {
        SystemdServiceControl control = controlWith(cmd -> {
            if ("is-active".equals(cmd[2])) {
                return active();
            }
            if ("show".equals(cmd[2])) {
                return new SystemdServiceControl.Result(0, "ActiveState=active\n", "");
            }
            if ("journalctl".equals(cmd[0])) {
                return new SystemdServiceControl.Result(1, "", "journal error\n");
            }
            return null;
        });
        ServiceControlPort.ServiceStatus status = control.status();
        assertEquals("active", status.activeState());
        assertTrue(status.recentLogs().isEmpty());
        assertTrue(status.diagnostic() == null);
    }
}
