# US-05 Implementation Proposal — n8n installation, systemd service and upgrade management

**Status:** APPROVED (owner, 2026-11-21 chat) — ALL PHASES DONE. Phase 1 (US-05.2) #17 Done · Phase 2 (US-05.3) #18 Done · Phase 3 (US-05.4) #20 Done: MCP activated by the agent end-to-end (owner-directed), token + API key stored, handshake + restart-persistence verified (commit 7ab258b) · **Last updated:** 2026-11-21 · **Owner:** jllopez · **Target:** this host (127.0.0.1-only, no Docker)
**Backlog:** epic #5 · #16 US-05.1 ✅ CLOSED · #23 US-05.5 ✅ CLOSED · #17 US-05.2 OPEN · #18 US-05.3 OPEN · #20 US-05.4 OPEN

## Goal

The manager (Java CLI, `io.github.joseluisll.n8nmanager`) helps maintain the local n8n
Community instance: dependency baseline (done), installation (done), systemd service
control, upgrades with safety checks, and activation of the instance's MCP capability.

**Scope boundaries:** npm only (Docker never used — owner decision 2026-11-21); Community
edition only (Enterprise/license keys out of scope and must be refused); all operations
local to this host, loopback-only.

## Current state (verified on the host, 2026-11-21)

| Component | Command run | Result | Verdict |
|---|---|---|---|
| n8n binary | `n8n --version` | **2.48.7** at `~/.local/bin/n8n` | installed, pinned |
| n8n service | `systemctl --user is-active n8n` | **active** (user unit, `Restart=on-failure`, PATH pins Node 24 first) | healthy |
| Listeners | `ss -tlnp` | `127.0.0.1:5678` + `127.0.0.1:5679` only — never 0.0.0.0/[::] | loopback-only ✔ |
| Health | `curl 127.0.0.1:5678/healthz` | `{"status":"ok"}` | ✔ |
| MCP endpoint | `POST /mcp-server/http` | **404 `MCP access is disabled`** | US-05.4 not started |
| Node | `node --version` via `~/.local/bin` | **v24.22.3** (user-local `~/tools/node24`, distro 22 untouched) | ✔ baseline |
| npm | `npm config get prefix` | **11.x**, prefix `~/tools/node-v24.22.3-linux-x64` | ✔ |
| Java | `java -version` / `ls /usr/lib/jvm` | default 17 on PATH; **21 present** (`java-21-openjdk-amd64`), pom targets 21 | ✔ (manager exports 21 explicitly) |
| Maven | `mvn -version` | **3.9.16** | ✔ |
| `deps status` command | `DependencyCommands.java` | implemented (US-05.5, commit 7a003136) | ✔ |
| Install procedure | `docs/install/{INSTALL.md,install-n8n.sh,install-deps.sh,n8n.service,n8n.env.example}` | committed, idempotency proven 3× (issue #16) | ✔ |
| Review findings #24/#25 | commits 121ed7f…808b065 | all seven + five findings fixed | ✔ |
| `~/.local/lib/node-libs` leftover | `ls -d` | **gone** (owner deleted it) | ✔ cleanup done |
| MCP activation UI step | — | not performed | owner action pending |
| `N8N_API_KEY` in env file | issue #16 record | empty placeholder | owner action pending |

Manager CLI today has only `deps status` (US-05.5) and a minimal REST `N8nClient`
(workflows/executions stubs for US-02/03). **Nothing yet exists for service control (US-05.2)
or upgrades (US-05.3)** — both are greenfield slices.

## Dependency graph

- **US-05.2** depends on nothing open — the working unit already exists; the slice is
  manager-side commands only. **First.**
- **US-05.3** depends on **US-05.2** (an upgrade must stop/start the service safely) and on
  the US-05.5 npm ≥11 `--allow-scripts` pitfall. The pinned `N8N_VERSION` currently lives
  inside `install-n8n.sh`; upgrading from the manager needs that pin in one place.
- **US-05.4** depends on **owner web-UI steps** (enable Instance-level MCP, generate MCP
  token, create `N8N_API_KEY`) — grounded: cannot be set via env vars in the beta. Agent
  work is docs + verification only, and verifies via US-05.2's restart to prove persistence.
- US-05.4 feeds **US-01.4** (manager-side MCP channel, epic #1 — separate epic, out of scope here).

## Requirements

- **FR-1 — Service control commands.** Manager commands `n8n service start|stop|restart|status|enable|disable` wrapping `systemctl --user` behind a hexagonal port (`ServiceControlPort`), so the CLI adapter and future REST/MCP adapters share one use case. (US-05.2)
- **FR-2 — Service state surfacing.** `n8n service status` reports active/failed state, PID, since, unit path and the last journal lines; failures (non-zero exit, crash-loop signature) surface as clear text with remediation, never silent exit 0. (US-05.2)
- **FR-3 — Upgrade command.** `n8n upgrade [--version X | --latest]` upgrades n8n core: resolves the target version, stops the service, runs `npm install -g --allow-scripts=<list> n8n@X`, restarts, health-checks. Community nodes (plugins) via `n8n nodes upgrade` equivalent or `npm` update of the community-packages list. (US-05.3)
- **FR-4 — Post-upgrade health gate.** After any upgrade the manager automatically probes `/healthz` on 127.0.0.1:5678 (bounded retries); failure is reported clearly with the rollback command. (US-05.3)
- **FR-5 — Data & config preservation.** Upgrades touch only the npm global tree — `~/.n8n` (data, encryption key) and `~/.config/n8n/n8n.env` are never written by an upgrade; the unit file and env file are backed up to a timestamped copy before the upgrade and restored on failure. (US-05.3)
- **FR-6 — Community-edition guard.** The upgrade path installs from the open-source npm distribution only and refuses/report any enterprise step: version must come from the public registry's `n8n` package (no `@n8n/enterprise*`, no license key ever set — guard rejects an `N8N_LICENSE_*` env appearing in the env file). (US-05.3)
- **FR-7 — Single version pin.** The expected n8n version moves to one place (`n8n.deps.expected.n8n` in `application.yml`, next to the existing node/npm/java/maven expectations); `install-n8n.sh` reads it or is updated in the same change, so `deps status` can also flag "n8n WRONG VERSION" after an upgrade. (US-05.3, closes the pin-drift risk)
- **FR-8 — MCP activation docs + verification.** A documented, reproducible activation procedure (owner UI steps enumerated: Settings → Instance-level MCP → Enable, generate MCP access token, create `N8N_API_KEY`) in `docs/install/`; after the owner enables it, the manager verifies `initialize → tools/list` handshake against `127.0.0.1:5678/mcp-server/http` with the stored token, and the setting survives a `n8n service restart`. (US-05.4)
- **FR-9 — Workflow exposure policy recorded.** The docs state the exposure choice (auto-expose ON, or an explicit documented list of exposed workflows) — owner decision, recorded in the repo docs. (US-05.4)
- **NFR-1 — Idempotency.** Every new command is re-runnable; `n8n service` commands are no-ops when the state already matches (start on active → "already active", exit 0).
- **NFR-2 — No Docker, loopback only.** All operations stay on-host; nothing ever binds or references a non-loopback address.
- **AC checks per requirement** live in the issue bodies (#17, #18, #20); plan steps below tag the ACs they prove.

## Implementation plan

### Phase 1 — US-05.2 service management (agent, ~1 slice)
- [ ] `ServiceControlPort` + `SystemdUserAdapter` (invokes `systemctl --user`, parses `is-active`/`show -p`); in-memory double for tests. (FR-1, FR-2)
- [ ] `ServiceCommands` CLI: start/stop/restart/status/enable/disable with already-in-state idempotence. (FR-1, NFR-1)
- [ ] Status rendering: active state, sub-state, PID, uptime, enablement, last 10 journal lines. (FR-2)
- [ ] Unit tests + full `mvn -B package`; live verify each command against the real unit (status, restart → `/healthz` 200 after, stop → start). (ACs #17)
- [ ] Commit (Refs #17), fail-closed review, issue comment, board flip.

### Phase 2 — US-05.3 upgrade management (agent, ~1 slice; needs Phase 1)
- [x] Version-pin single source: `n8n.deps.expected.n8n` in `application.yml`; `deps status` gained the n8n row; `install-n8n.sh` extracts the pin from it (loud fallback literal). (FR-7, D-1)
- [x] `n8n upgrade [--version X]`: pre-checks (deps, license guard) → backup unit+env → **stop service** → npm install with allow-scripts → version read-back → start → health gate → report old→new + rollback command. (FR-3, FR-4, FR-5, D-2)
- [x] Community-only guard: `N8N_LICENSE*` env line refuses the upgrade before anything runs; install source is the npm registry's open-source `n8n` package only. (FR-6)
- [x] Community-node (plugin) upgrade path: `n8n upgrade nodes`. (FR-3)
- [x] Tests: 12 upgrade unit tests (happy path, license refusal, deps refusal, invalid version, no-op at target, npm failure + restore, version mismatch, health-gate failure, backups, drift report, nodes no-op, nodes update). Full-mutation live run deliberately NOT executed — a real version change is owner-ordered (n8n DB migrations are one-way); live evidence = deps status 5/5 incl n8n, pin, no-op paths, installer extraction. (ACs #18)
- [ ] Commit b9c6793 pushed; fail-closed review pending → then issue comment + board flip.
- [x] Review verdict APPROVE (zero blocking); follow-ups fixed + test-pinned in 406365d; #18 commented, board Done.

### Phase 3 — US-05.4 MCP activation (owner steps first, then agent verification)
- [ ] OWNER: in the n8n web UI — create owner account if pending → Settings → Instance-level MCP → Enable → generate MCP access token → create `N8N_API_KEY`; store both in `~/.config/n8n/n8n.env` (secrets never in chat or repo). (ACs #20)
- [ ] OWNER: decide workflow exposure policy. (FR-9 — decision D-3)
- [ ] AGENT: write `docs/install/MCP-SETUP.md` reproducing the procedure for this n8n version (pin the UI-vs-env mechanism on the installed version). (FR-8)
- [ ] AGENT: verify handshake `initialize → tools/list` succeeds on loopback with the stored token; restart the service and verify persistence; confirm Community edition unmarked. (ACs #20, FR-8)
- [ ] Board flip for #20 only after the handshake evidence lands on the issue.

Phases 1 and 2 are serial (2 uses 1). Phase 3's owner steps can run anytime; the agent tail
follows them. US-05.2/05.3 do not require the API key; US-05.4 verification does (token).

## Rollback

- Phase 1: commands are additive; `git revert` the slice. The unit file itself is unchanged by the manager.
- Phase 2: every upgrade backs up unit + env (timestamped) and the previous version number is recorded in the upgrade report — rollback = `n8n upgrade --version <previous>` (or re-run `install-n8n.sh` after re-pinning). `~/.n8n` data is never touched by any upgrade path.
- Phase 3: deactivation is the reverse UI toggle; removing the token from the env file revokes access.

## Risks & open decisions

- **D-1 — Version pin single source (RESOLVED, owner approved 2026-11-21): `application.yml` expectation; `install-n8n.sh` is synced to it.** Today they are one file, but the manager cannot report version drift against a shell variable.
- **D-2 — Upgrade stop-vs-hot (RESOLVED, owner approved 2026-11-21): upgrade always stops the service first** (short, bounded downtime), never hot-installs — the US-05.1 evidence shows a plain `npm install -g` re-run churns the tree *under the live service* for minutes.
- **D-3 — MCP workflow exposure policy (agent default, conservative): explicit documented list** of exposed workflows recorded in `docs/install/MCP-SETUP.md` when Phase 3 lands (auto-expose ON remains available as a one-line doc change if the owner prefers).
- **R-1 — `systemctl --user` from the manager process:** works when the manager runs as this user with a session bus (the CLI case). If the manager ever runs from a non-login context without `XDG_RUNTIME_DIR`, the adapter must fail with a clear diagnostic naming the fix (`export XDG_RUNTIME_DIR=/run/user/$(id -u)`), not a cryptic dbus error. Pinned in FR-2.
- **R-2 — MCP beta mechanism may change with n8n versions:** the docs must pin the actual mechanism (UI setting) per installed version; a future upgrade re-checks it (note added to the upgrade checklist).
- **R-3 — npm ≥11 silently skips native postinstalls** without `--allow-scripts`; every install/upgrade command must pass the same allow-list the installer uses (pitfall already documented in `docs/DEPENDENCIES.md`).
- **R-4 — `N8N_API_KEY` still unset:** blocks US-05.4 verification and all of epics #1–#4 live work; flagged here so it doesn't surprise the later epics.

## References

- Issues: #5 (epic), #16, #17, #18, #20, #23, #24, #25.
- Repo: `docs/install/INSTALL.md`, `docs/install/install-n8n.sh`, `docs/install/install-deps.sh`, `docs/DEPENDENCIES.md`, `src/main/java/io/github/joseluisll/n8nmanager/{deps,cli}`.
- Skills/procedures: n8n-manager-implementation skill (slice loop, review gate, board rules).
