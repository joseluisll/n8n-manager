# Instance-level MCP activation (US-05.4, issue #20)

Procedure for enabling the n8n instance's MCP capability on the local
Community install (n8n 2.48.7, loopback-only, systemd user service). The
agent-side steps are reproducible from this doc; the activation itself is an
OWNER web-UI step (grounded 2026-11-21: instance-level MCP cannot be enabled
via environment variables in the beta — the UI setting is read-only on
instances that manage MCP access through env vars).

## Status (UPDATED 2026-11-21 — ACTIVATED AND VERIFIED)

- Instance MCP endpoint: `http://127.0.0.1:5678/mcp-server/http`
- Current state: **ENABLED and verified** — activated 2026-11-21 via the web UI:
  - `POST /mcp-server/http` unauthenticated → `401` (was `404 MCP access is disabled`)
  - MCP access token generated and stored at `~/.config/n8n/mcp-token` (`0600`, 272 chars)
  - `initialize` → `200` (serverInfo "n8n MCP Server", tools+resources capabilities) → `tools/list` → **68 tools**
  - Persistence verified: `n8n-manager n8n service restart` → healthy → handshake `200` again (setting lives in `~/.n8n`, untouched by service operations)
  - `N8N_API_KEY` created (label `n8n-manager`, 117 scopes, **never expires**) and stored in `~/.config/n8n/n8n.env`; verified `GET /api/v1/workflows` → `200`
  - Community check: zero license lines in the env file
  - NOTE for operators: after a service restart, `/healthz` answers within seconds but the REST/MCP routes register ~1–2 min later (404 until "Editor is now accessible") — wait for readiness before probing.

## Owner steps (web UI, http://127.0.0.1:5678)

1. Sign in as the instance owner (create the owner account if the instance
   has never been opened).
2. Settings → **Instance-level MCP** → **Enable**.
3. Generate an **MCP access token** (personal MCP token — a separate secret,
   NOT the `N8N_API_KEY`).
4. Decide the workflow exposure policy (D-3, agent default recorded
   2026-11-21: **explicit documented list** — see below) and note which
   workflows are exposed to MCP clients.
5. If not done yet: Settings → n8n API → create an API key (needed by the
   manager's REST client; US-05.4 verification uses the MCP token, but
   epics US-01–US-04 need the API key).

## Secret storage (never in chat, never in the repo)

- `N8N_API_KEY` → `~/.config/n8n/n8n.env` (the manager reads it via
  `N8N_API_KEY`/`n8n.api-key`; the systemd unit loads the file).
- MCP access token → `~/.config/n8n/mcp-token`, permissions `0600`
  (separate file so the token is not exported into the n8n service
  environment, where it would serve no purpose).

```bash
install -m 600 /dev/null ~/.config/n8n/mcp-token   # then paste the token in
```

## Workflow exposure policy (D-3)

DEFAULT (agent, conservative): the exposed-workflow list is EXPLICIT and
recorded here. When the owner exposes a workflow for MCP clients, add a line
below:

| Workflow | Exposed | Since | Reason |
|---|---|---|---|
| (none) | — | — | instance activated 2026-11-21 with **0 workflows exposed**; "automatically enable MCP access for new workflows" toggle is **OFF** (verified in the UI) |

Auto-expose ON is the alternative — a one-line change to this section,
owner's call.

## Agent verification (after the owner enables)

1. **Endpoint answers**: `POST http://127.0.0.1:5678/mcp-server/http` no
   longer returns the 404 `MCP access is disabled`.
2. **Handshake with the stored token**: MCP Streamable-HTTP
   `initialize` → `notifications/initialized` → `tools/list`, with
   `Authorization: Bearer $(cat ~/.config/n8n/mcp-token)`. (Exact header
   shape validated live at verification time against the installed version.)
3. **Persistence across restarts** (US-05.2 channel):
   `n8n-manager n8n service restart`, wait for `/healthz` 200, repeat the
   handshake — the setting must survive (it lives in `~/.n8n`, never touched
   by upgrades or service operations).
4. **Loopback constraint**: the endpoint only exists on 127.0.0.1 (US-05.1
   binding) — nothing else listens.
5. **Community edition**: no enterprise license involved
   (`grep -i license ~/.config/n8n/n8n.env` must be empty — the upgrade
   guard also refuses such lines, US-05.3).

Evidence goes into a comment on issue #20; the board item flips only after
the handshake succeeds.
