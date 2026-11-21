package io.github.joseluisll.n8nmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paths and endpoints for the n8n upgrade flow (US-05.3). Defaults match
 * the deployed US-05.1 layout; {@code ~} in a configured value expands to
 * the user home at binding time.
 */
@ConfigurationProperties(prefix = "n8n.upgrade")
public record UpgradeProperties(String envFile, String unitFile, String backupDir,
                                String healthUrl, Integer healthTimeoutSeconds) {

    public UpgradeProperties {
        if (envFile == null || envFile.isBlank()) {
            envFile = home() + "/.config/n8n/n8n.env";
        } else {
            envFile = expand(envFile);
        }
        if (unitFile == null || unitFile.isBlank()) {
            unitFile = home() + "/.config/systemd/user/n8n.service";
        } else {
            unitFile = expand(unitFile);
        }
        if (backupDir == null || backupDir.isBlank()) {
            backupDir = home() + "/.local/share/n8n-manager/upgrade-backups";
        } else {
            backupDir = expand(backupDir);
        }
        if (healthUrl == null || healthUrl.isBlank()) {
            // 127.0.0.1, never 'localhost': the instance binds the IPv4
            // loopback only (US-05.1) and 'localhost' can resolve to ::1.
            healthUrl = "http://127.0.0.1:5678/healthz";
        }
        if (healthTimeoutSeconds == null || healthTimeoutSeconds <= 0) {
            healthTimeoutSeconds = 180;
        }
    }

    private static String home() {
        return System.getProperty("user.home");
    }

    private static String expand(String value) {
        if (value.equals("~") || value.startsWith("~/")) {
            return home() + value.substring(1);
        }
        return value;
    }
}
