# n8n-manager

CLI tool to manage n8n instances via the [n8n REST API](https://docs.n8n.io/api/).

Built with Java 17, Spring Boot 3.5 and Spring Shell 3.4.

## Build

```bash
mvn package
```

## Run

```bash
export N8N_BASE_URL=https://n8n.example.com
export N8N_API_KEY=<your n8n API key>
java -jar target/n8n-manager-0.1.0-SNAPSHOT.jar
```

This starts an interactive shell. Available commands:

| Command | Description |
|---|---|
| `workflow-list` | List workflows on the instance |
| `workflow-get <id>` | Get a workflow by id |
| `workflow-activate <id>` | Activate a workflow |
| `workflow-deactivate <id>` | Deactivate a workflow |
| `execution-list` | List executions |
| `help` | List all commands |

Non-interactive single commands also work:

```bash
java -jar target/n8n-manager-0.1.0-SNAPSHOT.jar workflow-list
```

## License

[Apache-2.0](LICENSE)
