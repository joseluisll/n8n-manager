package io.github.joseluisll.n8nmanager.cli;

import io.github.joseluisll.n8nmanager.n8n.N8nClient;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.shell.jline.PromptProvider;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.stereotype.Component;

@ShellComponent
public class WorkflowCommands {

    private final N8nClient client;

    public WorkflowCommands(N8nClient client) {
        this.client = client;
    }

    @org.springframework.shell.standard.ShellMethod("List workflows on the configured n8n instance")
    public String workflowList() {
        return client.listWorkflows();
    }

    @org.springframework.shell.standard.ShellMethod("Get a workflow by id")
    public String workflowGet(String id) {
        return client.getWorkflow(id);
    }

    @org.springframework.shell.standard.ShellMethod("Activate a workflow by id")
    public String workflowActivate(String id) {
        return client.activateWorkflow(id);
    }

    @org.springframework.shell.standard.ShellMethod("Deactivate a workflow by id")
    public String workflowDeactivate(String id) {
        return client.deactivateWorkflow(id);
    }

    @org.springframework.shell.standard.ShellMethod("List executions on the configured n8n instance")
    public String executionList() {
        return client.listExecutions();
    }

    @Component
    public static class Prompt implements PromptProvider {
        @Override
        public AttributedString getPrompt() {
            return new AttributedString("n8n-manager:> ",
                    AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
        }
    }
}
