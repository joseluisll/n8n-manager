package io.github.joseluisll.n8nmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for a single n8n instance.
 */
@ConfigurationProperties(prefix = "n8n")
public record N8nProperties(String baseUrl, String apiKey) {

    public N8nProperties {
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }
}
