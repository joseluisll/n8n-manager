package io.github.joseluisll.n8nmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Expected versions of the local-stack dependencies (US-05.5).
 *
 * <p>Expressions are either an exact version ("3.9.16") or a minimum bound
 * (">=24.0.0"). Defaults are applied by {@link io.github.joseluisll.n8nmanager.deps.DependencyChecker}
 * when a value is absent. {@code javaHome} is the JDK 21 installation the
 * manager is probed against (same path as {@code docs/install/install-deps.sh});
 * it deliberately does NOT fall back to the {@code JAVA_HOME} environment
 * variable, which on this host is a Java 17 used by other tooling.
 */
@ConfigurationProperties(prefix = "n8n.deps")
public record DependencyProperties(Expected expected, String javaHome) {

    public record Expected(String node, String npm, String java, String maven, String n8n) {
    }
}
