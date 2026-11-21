package io.github.joseluisll.n8nmanager.deps;

import io.github.joseluisll.n8nmanager.config.DependencyProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Probes the local-stack dependencies (node, npm, java, maven) and reports
 * found vs expected versions with a diagnostic for every failure
 * (US-05.5). Expectations come from {@code n8n.deps.expected.*}; the
 * canonical versions and procedures live in {@code docs/DEPENDENCIES.md}.
 */
@Service
public class DependencyChecker {

    /** A runner seam so tests can stub command execution. */
    @FunctionalInterface
    public interface CommandRunner {
        Result run(String... command) throws Exception;
    }

    public record Result(int exitCode, String stdout, String stderr) {
    }

    public record DependencyStatus(
            String name, String expected, String found, boolean ok, String diagnostic) {
    }

    private static final Map<String, String> DEFAULT_EXPECTATIONS = Map.of(
            "node", ">=24.0.0",
            "npm", ">=10.0.0",
            "java", ">=21",
            "maven", ">=3.9.0");

    private static final Map<String, String> REMEDIATION = Map.of(
            "node", "n8n 2.x refuses Node < 24 - install the user-local Node 24 LTS: docs/install/install-deps.sh (see docs/DEPENDENCIES.md)",
            "npm", "npm ships with Node 24 - installing Node fixes this too (docs/install/install-deps.sh)",
            "java", "the manager builds and runs on Java 21 - install OpenJDK 21 and point the build at it (docs/DEPENDENCIES.md)",
            "maven", "install Maven 3.9.x per docs/DEPENDENCIES.md");

    private static final Map<String, String[]> PROBES = Map.of(
            "node", new String[]{"node", "--version"},
            "npm", new String[]{"npm", "--version"},
            "java", new String[]{"java", "-version"},
            "maven", new String[]{"mvn", "--version"});

    private final DependencyProperties properties;
    private final CommandRunner runner;

    @org.springframework.beans.factory.annotation.Autowired
    public DependencyChecker(DependencyProperties properties) {
        this(properties, DependencyChecker::execute);
    }

    /** Test constructor: inject a stubbed runner. */
    DependencyChecker(DependencyProperties properties, CommandRunner runner) {
        this.properties = properties;
        this.runner = runner;
    }

    public List<DependencyStatus> checkAll() {
        List<DependencyStatus> statuses = new ArrayList<>();
        statuses.add(check("node"));
        statuses.add(check("npm"));
        statuses.add(check("java"));
        statuses.add(check("maven"));
        return statuses;
    }

    public DependencyStatus check(String name) {
        String expected = expectedVersion(name);
        String[] probe = PROBES.get(name);
        String found;
        try {
            Result result = runner.run(probe);
            if (result.exitCode() != 0) {
                return failed(name, expected, null,
                        name + " exists but exits " + result.exitCode() + " - " + REMEDIATION.get(name));
            }
            found = extractVersion(name, result);
            if (found == null) {
                return failed(name, expected, null,
                        "could not parse " + name + " version output - " + REMEDIATION.get(name));
            }
        } catch (Exception e) {
            return failed(name, expected, null,
                    name + " not found on PATH (" + e.getMessage() + ") - " + REMEDIATION.get(name));
        }
        boolean ok;
        try {
            ok = VersionExpression.parse(expected).isSatisfiedBy(found);
        } catch (IllegalArgumentException e) {
            return failed(name, expected, found, "invalid expected version '" + expected + "': " + e.getMessage());
        }
        if (ok) {
            return new DependencyStatus(name, expected, found, true, null);
        }
        return failed(name, expected, found,
                "found " + found + ", expected " + expected + " - " + REMEDIATION.get(name));
    }

    private static DependencyStatus failed(String name, String expected, String found, String diagnostic) {
        return new DependencyStatus(name, expected, found, false, diagnostic);
    }

    private String expectedVersion(String name) {
        String configured = switch (name) {
            case "node" -> properties != null && properties.expected() != null
                    ? properties.expected().node() : null;
            case "npm" -> properties != null && properties.expected() != null
                    ? properties.expected().npm() : null;
            case "java" -> properties != null && properties.expected() != null
                    ? properties.expected().java() : null;
            case "maven" -> properties != null && properties.expected() != null
                    ? properties.expected().maven() : null;
            default -> null;
        };
        return configured != null && !configured.isBlank() ? configured : DEFAULT_EXPECTATIONS.get(name);
    }

    static String extractVersion(String name, Result result) {
        String text = name.equals("java") ? result.stderr() + "\n" + result.stdout() : result.stdout();
        List<String> lines = nonBlankLines(text);
        if (lines.isEmpty()) {
            return null;
        }
        String first = lines.get(0);
        try {
            return switch (name) {
                // "v24.22.3"
                case "node", "npm" -> firstToken(first);
                // "openjdk version "21.0.13.1" 2026-11-17"
                case "java" -> quotedToken(first);
                // "Apache Maven 3.9.16 (2bdd9fdd...)"
                case "maven" -> mavenToken(first);
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstToken(String line) {
        String v = line.trim();
        return v.startsWith("v") ? v.substring(1) : v;
    }

    private static String quotedToken(String line) {
        int open = line.indexOf('"');
        int close = open < 0 ? -1 : line.indexOf('"', open + 1);
        return open >= 0 && close > open ? line.substring(open + 1, close) : null;
    }

    private static String mavenToken(String line) {
        String[] tokens = line.trim().split("\\s+");
        return tokens.length >= 3 ? tokens[2] : null;
    }

    private static List<String> nonBlankLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
        }
        return lines;
    }

    /** Renders the human-readable report with a diagnostic line per failure. */
    public String render(List<DependencyStatus> statuses) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<DependencyStatus>> grouped = new LinkedHashMap<>();
        grouped.put("ok", new ArrayList<>());
        grouped.put("failed", new ArrayList<>());
        for (DependencyStatus s : statuses) {
            grouped.get(s.ok() ? "ok" : "failed").add(s);
        }
        sb.append("Dependency state (expected versions: docs/DEPENDENCIES.md)\n");
        for (DependencyStatus s : statuses) {
            String state = s.ok() ? "OK" : (s.found() == null ? "MISSING" : "WRONG VERSION");
            sb.append(String.format("  %-6s %-13s expected %-10s found %s%n",
                    s.name(), state, s.expected(), s.found() == null ? "-" : s.found()));
        }
        if (grouped.get("failed").isEmpty()) {
            sb.append("All dependencies satisfied.\n");
        } else {
            sb.append("Problems found:\n");
            for (DependencyStatus s : grouped.get("failed")) {
                sb.append("  [").append(s.name()).append("] ").append(s.diagnostic()).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    private static Result execute(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tout = drain(process.getInputStream(), out);
        Thread terr = drain(process.getErrorStream(), err);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(command[0] + " probe timed out");
        }
        tout.join(5000);
        terr.join(5000);
        return new Result(process.exitValue(), out.toString(), err.toString());
    }

    private static Thread drain(java.io.InputStream stream, StringBuilder target) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // probe stream ended
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
