package io.github.joseluisll.n8nmanager.cli;

import io.github.joseluisll.n8nmanager.service.ServiceControlPort;
import io.github.joseluisll.n8nmanager.service.SystemdServiceControl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceCommandsTest {

    /** In-memory port double recording the calls it receives. */
    private static class FakePort implements ServiceControlPort {
        final List<String> calls = new ArrayList<>();
        ServiceControlPort.ServiceResult next =
                new ServiceControlPort.ServiceResult("start", false, true, "already");

        @Override
        public ServiceResult start() {
            calls.add("start");
            return next;
        }

        @Override
        public ServiceResult stop() {
            calls.add("stop");
            return next;
        }

        @Override
        public ServiceResult restart() {
            calls.add("restart");
            return next;
        }

        @Override
        public ServiceResult enable() {
            calls.add("enable");
            return next;
        }

        @Override
        public ServiceResult disable() {
            calls.add("disable");
            return next;
        }

        @Override
        public ServiceStatus status() {
            calls.add("status");
            return new ServiceStatus("n8n.service", "active", "running", "1808",
                    "Tue 2026-11-21 19:52:03 CET", "enabled",
                    "/home/jllopez/.config/systemd/user/n8n.service",
                    List.of("Nov 21 19:52:10 host n8n[1808]: ready"), null);
        }
    }

    @Test
    void commandsDelegateToThePortAndPrefixOk() {
        FakePort port = new FakePort();
        port.next = new ServiceControlPort.ServiceResult("restart", true, true, "n8n.service restarted");
        ServiceCommands commands = new ServiceCommands(port, new SystemdServiceControl(
                new io.github.joseluisll.n8nmanager.config.ServiceProperties("n8n.service", 5)));
        assertEquals("OK: n8n.service restarted", commands.restart());
        assertEquals(List.of("restart"), port.calls);
    }

    @Test
    void failedResultIsPrefixedFailed() {
        FakePort port = new FakePort();
        port.next = new ServiceControlPort.ServiceResult("start", false, false,
                "n8n.service start failed (exit 1): boom");
        ServiceCommands commands = new ServiceCommands(port, new SystemdServiceControl(
                new io.github.joseluisll.n8nmanager.config.ServiceProperties("n8n.service", 5)));
        String out = commands.start();
        assertTrue(out.startsWith("FAILED: "));
        assertTrue(out.contains("boom"));
    }

    @Test
    void missingSessionSurfacesAsFailedLineWithTheFix() {
        FakePort port = new FakePort() {
            @Override
            public ServiceResult start() {
                throw new IllegalStateException(
                        "could not query the unit state: no usable systemd user session (bus gone). "
                                + "Fix: export XDG_RUNTIME_DIR=/run/user/$(id -u)");
            }
        };
        ServiceCommands commands = new ServiceCommands(port, new SystemdServiceControl(
                new io.github.joseluisll.n8nmanager.config.ServiceProperties("n8n.service", 5)));
        String out = commands.start();
        assertTrue(out.startsWith("FAILED: "), "R-1: actionable FAILED line, never a stack trace");
        assertTrue(out.contains("XDG_RUNTIME_DIR"));
    }

    @Test
    void statusRendersThroughTheControlRenderer() {
        FakePort port = new FakePort();
        ServiceCommands commands = new ServiceCommands(port, new SystemdServiceControl(
                new io.github.joseluisll.n8nmanager.config.ServiceProperties("n8n.service", 5)));
        String out = commands.status();
        assertTrue(out.contains("Active:      active (running)"));
        assertTrue(out.contains("n8n[1808]: ready"));
        assertEquals(List.of("status"), port.calls);
    }
}
