package io.github.joseluisll.n8nmanager.cli;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.shell.command.CommandCatalog;
import org.springframework.shell.command.CommandRegistration;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the workflow commands are registered in Spring Shell's command
 * catalog. Regression coverage for the @Component -> @ShellComponent fix
 * (US-05.5 review finding F-1, review t_f0a6b78d, issue #24): a plain
 * @Component bean is invisible to Spring Shell's command scan.
 */
@SpringBootTest
class WorkflowCommandsRegistrationTest {

    @Autowired
    private CommandCatalog commandCatalog;

    @Test
    void workflowCommandsAreRegisteredInShellCatalog() {
        Set<String> commandNames = commandCatalog.getRegistrations().keySet();

        for (String expected : new String[] {
                "workflow-list", "workflow-get",
                "workflow-activate", "workflow-deactivate",
                "execution-list"}) {
            assertTrue(commandNames.contains(expected),
                    () -> "command '" + expected + "' not registered; catalog has: " + commandNames);
        }
    }

    @Test
    void workflowListCommandIsInvocableWithOneRequiredArg() {
        CommandRegistration reg = commandCatalog.getRegistrations().get("workflow-get");
        assertTrue(reg != null, "workflow-get not registered");
        assertEquals(1, reg.getOptions().stream()
                .filter(o -> o.isRequired())
                .count(), "workflow-get should take one required id argument");
    }
}
