package io.github.joseluisll.n8nmanager.cli;

import io.github.joseluisll.n8nmanager.deps.DependencyChecker;
import io.github.joseluisll.n8nmanager.deps.DependencyChecker.DependencyStatus;
import org.springframework.shell.standard.ShellCommandGroup;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

import java.util.List;

/**
 * Dependency-state commands (US-05.5).
 */
@ShellComponent
@ShellCommandGroup("Dependencies")
public class DependencyCommands {

    private final DependencyChecker checker;

    public DependencyCommands(DependencyChecker checker) {
        this.checker = checker;
    }

    @ShellMethod(key = "deps status",
            value = "Report the local-stack dependency state (node, npm, java, maven): present/missing and version vs expected")
    public String depsStatus() {
        List<DependencyStatus> statuses = checker.checkAll();
        return checker.render(statuses);
    }
}
