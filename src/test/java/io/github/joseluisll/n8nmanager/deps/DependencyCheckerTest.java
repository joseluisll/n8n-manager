package io.github.joseluisll.n8nmanager.deps;

import io.github.joseluisll.n8nmanager.config.DependencyProperties;
import io.github.joseluisll.n8nmanager.deps.DependencyChecker.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyCheckerTest {

    /** Test JDK home; DependencyChecker probes <home>/bin/java, not PATH java (NF-3). */
    private static final String TEST_JAVA_HOME = "/opt/test-jdk21";
    private static final String TEST_JAVA_BIN = TEST_JAVA_HOME + "/bin/java";
    private static final Result JAVA_21 =
            new Result(0, "", "openjdk version \"21.0.13.1\" 2026-11-17\n");
    private static final Result JAVA_17 =
            new Result(0, "", "openjdk version \"17.0.20.1\" 2026-08-18\n");
    private static final Result MAVEN_OK =
            new Result(0, "Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)\n", "");

    private static DependencyProperties props(String node, String npm, String java, String maven) {
        return new DependencyProperties(
                new DependencyProperties.Expected(node, npm, java, maven), TEST_JAVA_HOME);
    }

    private static DependencyChecker checkerWith(DependencyProperties props,
                                                 java.util.Map<String, Result> canned) {
        return new DependencyChecker(props, command -> {
            Result r = canned.get(command[0]);
            if (r == null) {
                throw new java.io.IOException("not found on PATH: " + command[0]);
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
                        TEST_JAVA_BIN, JAVA_21,
                        "mvn", MAVEN_OK));
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
                        TEST_JAVA_BIN, JAVA_21,
                        "mvn", MAVEN_OK));
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
                        TEST_JAVA_BIN, JAVA_21));
        // maven not in the map -> runner throws -> MISSING
        DependencyChecker.DependencyStatus maven = checker.check("maven");
        assertFalse(maven.ok());
        assertEquals(null, maven.found());
        assertNotNull(maven.diagnostic());
        assertTrue(maven.diagnostic().toLowerCase().contains("not found"));
        assertTrue(maven.diagnostic().contains("docs/DEPENDENCIES.md"));
    }

    @Test
    void probesJdk21HomeNotPathJava() {
        // NF-3: PATH java is 17 (user default) while the manager runs on the
        // JDK 21 at the configured home; the check must probe the home and OK.
        DependencyChecker checker = checkerWith(
                props(">=24.0.0", ">=10.0.0", ">=21", ">=3.9.0"),
                java.util.Map.of(
                        "node", new Result(0, "v24.22.3\n", ""),
                        "npm", new Result(0, "11.19.1\n", ""),
                        TEST_JAVA_BIN, JAVA_21,
                        "java", JAVA_17,
                        "mvn", MAVEN_OK));
        DependencyChecker.DependencyStatus java = checker.check("java");
        assertTrue(java.ok(), () -> "java should be satisfied: " + java.diagnostic());
        assertEquals("21.0.13.1", java.found());
        assertNotNull(java.diagnostic(), "PATH-17 vs JDK-21 split must produce a note");
        assertTrue(java.diagnostic().contains("PATH java is 17.0.20.1"));
        assertTrue(java.diagnostic().contains("export JAVA_HOME=" + TEST_JAVA_HOME));
    }

    @Test
    void noNoteWhenPathJavaIsAlso21() {
        DependencyChecker checker = checkerWith(
                props(null, null, null, null),
                java.util.Map.of(
                        TEST_JAVA_BIN, JAVA_21,
                        "java", JAVA_21));
        DependencyChecker.DependencyStatus java = checker.check("java");
        assertTrue(java.ok());
        assertNull(java.diagnostic());
    }

    @Test
    void failsWithInstallHintWhenJdk21HomeMissing() {
        DependencyChecker checker = checkerWith(
                props(null, null, null, null),
                java.util.Map.of(
                        // PATH java present at 17 - must not satisfy the check
                        "java", JAVA_17));
        DependencyChecker.DependencyStatus java = checker.check("java");
        assertFalse(java.ok(), "missing JDK 21 home must fail even with PATH java present");
        assertNull(java.found());
        assertNotNull(java.diagnostic());
        assertTrue(java.diagnostic().contains("OpenJDK 21 not found at " + TEST_JAVA_HOME));
        assertTrue(java.diagnostic().contains("apt-get install openjdk-21-jdk"));
    }

    @Test
    void renderPrintsNoteForOkButCaveatedJava() {
        DependencyChecker checker = checkerWith(
                props(">=24.0.0", ">=10.0.0", ">=21", ">=3.9.0"),
                java.util.Map.of(
                        "node", new Result(0, "v24.22.3\n", ""),
                        "npm", new Result(0, "11.19.1\n", ""),
                        TEST_JAVA_BIN, JAVA_21,
                        "java", JAVA_17,
                        "mvn", MAVEN_OK));
        String report = checker.render(checker.checkAll());
        assertTrue(report.contains("All dependencies satisfied."));
        assertTrue(report.contains("note  [java]"));
        assertTrue(report.contains("PATH java is 17.0.20.1"));
        assertFalse(report.contains("Problems found:"));
    }

    @Test
    void renderNamesEveryFailedDependency() {
        DependencyChecker checker = checkerWith(
                props(">=24.0.0", ">=10.0.0", ">=21", ">=3.9.0"),
                java.util.Map.of(
                        "node", new Result(0, "v22.22.1\n", ""),
                        "npm", new Result(0, "9.2.0\n", ""),
                        TEST_JAVA_BIN, JAVA_21,
                        "mvn", MAVEN_OK));
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
                        TEST_JAVA_BIN, JAVA_21,
                        "mvn", MAVEN_OK));
        String report = checker.render(checker.checkAll());
        assertTrue(report.contains("All dependencies satisfied."));
    }
}
