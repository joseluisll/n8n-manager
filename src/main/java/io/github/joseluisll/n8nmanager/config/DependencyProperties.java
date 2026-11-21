package io.github.joseluisll.n8nmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Expected versions of the local-stack dependencies (US-05.5).
 *
 * <p>Expressions are either an exact version ("3.9.16") or a minimum bound
 * (">=24.0.0"). Defaults are applied by {@link io.github.joseluisll.n8nmanager.deps.DependencyChecker}
 * when a value is absent.
 */
@ConfigurationProperties(prefix = "n8n.deps")
public record DependencyProperties(Expected expected) {

    public record Expected(String node, String npm, String java, String maven) {
    }
}
