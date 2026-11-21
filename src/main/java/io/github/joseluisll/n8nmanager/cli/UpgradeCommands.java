package io.github.joseluisll.n8nmanager.cli;

import io.github.joseluisll.n8nmanager.upgrade.UpgradeService;
import org.springframework.shell.standard.ShellCommandGroup;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * Upgrade commands for the local n8n Community instance (US-05.3): core
 * version upgrades with safety checks (stop-first, backups, health gate,
 * Community-only guard) and community-node (plugin) updates.
 */
@ShellComponent
@ShellCommandGroup("n8n upgrade")
public class UpgradeCommands {

    private final UpgradeService upgrade;

    public UpgradeCommands(UpgradeService upgrade) {
        this.upgrade = upgrade;
    }

    @ShellMethod(key = "n8n upgrade",
            value = "Upgrade the local n8n Community instance: stops the service, installs the pinned (or given) version, restarts and health-gates")
    public String upgrade(@ShellOption(defaultValue = "") String version) {
        UpgradeService.Result r = upgrade.upgrade(version);
        return (r.success() ? "OK: " : "FAILED: ") + r.message();
    }

    @ShellMethod(key = "n8n upgrade nodes",
            value = "Update the installed n8n community nodes (plugins), then restart and health-gate")
    public String upgradeNodes() {
        UpgradeService.NodesResult r = upgrade.upgradeNodes();
        return (r.success() ? "OK: " : "FAILED: ") + r.message();
    }

    @ShellMethod(key = "n8n upgrade pin",
            value = "Show the configured n8n version pin (single source: application.yml n8n.deps.expected.n8n)")
    public String pin() {
        return "n8n pin (application.yml n8n.deps.expected.n8n): " + upgrade.pin();
    }
}
