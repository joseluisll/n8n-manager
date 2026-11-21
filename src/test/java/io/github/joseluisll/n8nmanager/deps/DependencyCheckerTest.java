package io.github.joseluisll.n8nmanager.deps;

import io.github.joseluisll.n8nmanager.config.DependencyProperties;
import io.github.joseluisll.n8nmanager.deps.DependencyChecker.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyCheckerTest {

    private static DependencyProperties props(String node, String npm, String java, String maven) {
        return new DependencyProperties(new DependencyProperties.Expected(node, npm, java, maven));
    }

    private static DependencyChecker checkerWith(DependencyProperties props,
                                                 java.util.Map<String, Result> canned) {
        return new DependencyChecker(props, command -> {
            String tool = command[0];
            Result r = canned.get(tool);
            if (r == null) {
                throw new java.io.IOException("not found on PATH: " + tool);
            }
            return r;
        });
    }

    @Test
    void reportsAllSatisfiedOnCurrentBaseline() {
        DependencyChecker checker = checkerWith(
                props(">=24.0.0", ">=10.0.0", ">=21", ">=3.9.0"),
                java.util.Map.of(
                        "node", new Result(0, "v24.22.3\n", ""),
                        "npm", new Result(0, "11.19.1\n", ""),
                        "java", new Result(0, "", "openjdk version \"21.0.13.1\" 2026-11-17\n"),
                        "mvn", new Result(0, "Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)\n", "")));
        List<DependencyChecker.DependencyStatus> statuses = checker.checkAll();
        for (DependencyChecker.DependencyStatus s : statuses) {
            assertTrue(s.ok(), () -> s.name() + " should be satisfied: " + s.diagnostic());
            assertEquals(null, s.diagnostic());
        }
        assertEquals(4, statuses.size());
    }

    @Test
    void flagsWrongNodeMajorWithRemediation() {
        DependencyChecker checker = checkerWith(
                props(">=24.0.0", ">=10.0.0", ">=21", ">=3.9.0"),
                java.util.Map.of(
                        "node", new Result(0, "v22.22.1\n", ""),
                        "npm", new Result(0, "9.2.0\n", ""),
                        "java", new Result(0, "", "openjdk version \"21.0.13.1\" 2026-11-17\n"),
                        "mvn", new Result(0, "Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)\n", "")));
        DependencyChecker.DependencyStatus node = checker.check("node");
        assertFalse(node.ok());
        assertEquals("22.22.1", node.found());
        assertNotNull(node.diagnostic());
        assertTrue(node.diagnostic().contains(">=24.0.0"));
        assertTrue(node.diagnostic().contains("install-deps.sh"), "diagnostic names the fix");
        DependencyChecker.DependencyStatus npm = checker.check("npm");
        assertFalse(npm.ok());
    }

    @Test
    void flagsMissingToolWithDiagnostic() {
        DependencyChecker checker = checkerWith(
                props(null, null, null, null),
                java.util.Map.of(
                        "node", new Result(0, "v24.22.3\n", ""),
                        "npm", new Result(0, "11.19.1\n", ""),
                        "java", new Result(0, "", "openjdk version \"21.0.13.1\" 2026-11-17\n")));
        // maven not in the map -> runner throws -> MISSING
        DependencyChecker.DependencyStatus maven = checker.check("maven");
        assertFalse(maven.ok());
        assertEquals(null, maven.found());
        assertNotNull(maven.diagnostic());
        assertTrue(maven.diagnostic().toLowerCase().contains("not found"));
        assertTrue(maven.diagnostic().contains("docs/DEPENDENCIES.md"));
    }

    @Test
    void renderNamesEveryFailedDependency() {
        DependencyChecker checker = checkerWith(
                props(">=24.0.0", ">=10.0.0", ">=21", ">=3.9.0"),
                java.util.Map.of(
                        "node", new Result(0, "v22.22.1\n", ""),
                        "npm", new Result(0, "9.2.0\n", ""),
                        "java", new Result(0, "", "openjdk version \"21.0.13.1\" 2026-11-17\n"),
                        "mvn", new Result(0, "Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)\n", "")));
        String report = checker.render(checker.checkAll());
        assertTrue(report.contains("node"));
        assertTrue(report.contains("WRONG VERSION"));
        assertTrue(report.contains("[node]"));
        assertFalse(report.contains("All dependencies satisfied"));
    }

    @Test
    void renderSaysAllSatisfiedWhenGreen() {
        DependencyChecker checker = checkerWith(
                props(">=24.0.0", ">=10.0.0", ">=21", ">=3.9.0"),
                java.util.Map.of(
                        "node", new Result(0, "v24.22.3\n", ""),
                        "npm", new Result(0, "11.19.1\n", ""),
                        "java", new Result(0, "", "openjdk version \"21.0.13.1\" 2026-11-17\n"),
                        "mvn", new Result(0, "Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)\n", "")));
        String report = checker.render(checker.checkAll());
        assertTrue(report.contains("All dependencies satisfied."));
    }
}
