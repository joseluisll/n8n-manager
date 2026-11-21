package io.github.joseluisll.n8nmanager.cli;

import io.github.joseluisll.n8nmanager.service.ServiceControlPort;
import io.github.joseluisll.n8nmanager.service.SystemdServiceControl;
import org.springframework.shell.standard.ShellCommandGroup;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

import java.util.function.Supplier;

/**
 * systemd service management commands for the local n8n instance (US-05.2).
 * All actions are idempotent no-ops when the unit is already in the requested
 * state (NFR-1); failures surface as FAILED lines with diagnostics (FR-2) —
 * a missing systemd user session yields the actionable XDG_RUNTIME_DIR fix
 * (R-1), never a raw stack trace.
 */
@ShellComponent
@ShellCommandGroup("n8n service")
public class ServiceCommands {

    private final ServiceControlPort service;
    private final SystemdServiceControl renderer;

    public ServiceCommands(ServiceControlPort service, SystemdServiceControl renderer) {
        this.service = service;
        this.renderer = renderer;
    }

    @ShellMethod(key = "n8n service start",
            value = "Start the local n8n systemd service (no-op when already active)")
    public String start() {
        return call(service::start);
    }

    @ShellMethod(key = "n8n service stop",
            value = "Stop the local n8n systemd service (no-op when already inactive)")
    public String stop() {
        return call(service::stop);
    }

    @ShellMethod(key = "n8n service restart",
            value = "Restart the local n8n systemd service (starts it when inactive)")
    public String restart() {
        return call(service::restart);
    }

    @ShellMethod(key = "n8n service status",
            value = "Show the local n8n service state, unit properties and recent logs")
    public String status() {
        return renderer.render(service.status());
    }

    @ShellMethod(key = "n8n service enable",
            value = "Enable the n8n service to start at login (no-op when already enabled)")
    public String enable() {
        return call(service::enable);
    }

    @ShellMethod(key = "n8n service disable",
            value = "Disable the n8n service from starting at login (no-op when already disabled)")
    public String disable() {
        return call(service::disable);
    }

    private String call(Supplier<ServiceControlPort.ServiceResult> action) {
        try {
            ServiceControlPort.ServiceResult result = action.get();
            return (result.success() ? "OK: " : "FAILED: ") + result.message();
        } catch (IllegalStateException e) {
            return "FAILED: " + e.getMessage();
        }
    }
}
