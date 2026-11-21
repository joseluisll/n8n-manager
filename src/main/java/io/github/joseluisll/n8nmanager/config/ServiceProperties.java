package io.github.joseluisll.n8nmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The systemd user unit that runs the local n8n instance (US-05.2).
 *
 * <p>Defaults are applied at binding time so absent keys fall back to the
 * deployed unit's names ({@code n8n.service}, 10 journal lines).
 */
@ConfigurationProperties(prefix = "n8n.service")
public record ServiceProperties(String unit, Integer journalLines) {

    public ServiceProperties {
        if (unit == null || unit.isBlank()) {
            unit = "n8n.service";
        }
        if (journalLines == null || journalLines < 0) {
            journalLines = 10;
        }
    }
}
