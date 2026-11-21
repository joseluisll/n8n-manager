package io.github.joseluisll.n8nmanager.n8n;

import io.github.joseluisll.n8nmanager.config.N8nProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Minimal client for the n8n public REST API (/{basePath} with X-N8N-API-KEY auth).
 * Endpoints implemented as needed; see https://docs.n8n.io/api/
 */
@Component
public class N8nClient {

    private final RestClient restClient;

    public N8nClient(N8nProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-N8N-API-KEY", properties.apiKey() == null ? "" : properties.apiKey())
                .build();
    }

    public String listWorkflows() {
        return restClient.get().uri("/api/v1/workflows").retrieve().body(String.class);
    }

    public String getWorkflow(String workflowId) {
        return restClient.get().uri("/api/v1/workflows/{id}", workflowId).retrieve().body(String.class);
    }

    public String activateWorkflow(String workflowId) {
        return restClient.post().uri("/api/v1/workflows/{id}/activate", workflowId)
                .retrieve().body(String.class);
    }

    public String deactivateWorkflow(String workflowId) {
        return restClient.post().uri("/api/v1/workflows/{id}/deactivate", workflowId)
                .retrieve().body(String.class);
    }

    public String listExecutions() {
        return restClient.get().uri("/api/v1/executions").retrieve().body(String.class);
    }
}
