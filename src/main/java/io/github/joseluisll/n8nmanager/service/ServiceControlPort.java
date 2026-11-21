package io.github.joseluisll.n8nmanager.service;

import java.util.List;

/**
 * Port for controlling the local n8n systemd service (US-05.2). The CLI
 * adapter depends on this port only; the systemd user-session adapter is the
 * one production implementation, and tests substitute an in-memory double.
 */
public interface ServiceControlPort {

    /** Outcome of a lifecycle action; {@code changed} is false when the unit was already in the requested state (idempotent no-op, NFR-1). */
    record ServiceResult(String action, boolean changed, boolean success, String message) {
    }

    /** Observed unit state for {@code status}; {@code diagnostic} carries an error when the state itself could not be probed. */
    record ServiceStatus(String unit, String activeState, String subState, String mainPid,
                         String since, String enabledState, String fragmentPath,
                         List<String> recentLogs, String diagnostic) {
    }

    ServiceResult start();

    ServiceResult stop();

    ServiceResult restart();

    ServiceResult enable();

    ServiceResult disable();

    ServiceStatus status();
}
