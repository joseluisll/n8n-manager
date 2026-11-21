# n8n local installation procedure (US-05.1)

Target state: a single n8n Community instance on this host, bound to
127.0.0.1 only, run as a systemd user service, with the n8n public API
key available to the manager. Decisions recorded here (owner,
2026-11-21): npm install, NO Docker anywhere, Community edition only.

The procedure is idempotent: re-running any step on an already
installed system either does nothing or converges to the same state.

## One-shot procedure (recommended)

```bash
cd /home/jllopez/code/n8n-manager
docs/install/install-n8n.sh
```

The script verifies the prerequisites, installs n8n under the
user-local npm prefix, writes the environment file (secrets are never
overwritten), installs the systemd user unit, enables linger and
verifies loopback-only binding + health. Re-running it on an installed
host is a no-op ("ALL STEPS CONVERGED" with every step already met).
The sections below document what each step does and the manual
equivalents.

## 1. Prerequisites (US-05.5)

- Node.js >= 24 — REQUIRED (grounded 2026-11-21: n8n 2.48.7 refuses
  Node 22 with `Please use a Node.js version that satisfies the
  following version range: >=24.0.0`, and the distro Node 22 made the
  unit crash-loop). The Node runtime itself is provisioned by
  US-05.5: `docs/install/install-deps.sh` installs a user-local Node
  24 LTS (pinned in `docs/DEPENDENCIES.md`) under `~/tools/node24`
  with symlinks in `~/.local/bin`. The n8n installer only VERIFIES
  this prerequisite.
- npm >= 11 (this host: 11.19.1) — npm >= 11 SKIPS native postinstall
  builds unless an explicit `--allow-scripts` list is passed (pitfall
  recorded in `docs/DEPENDENCIES.md`).
- Build tools: make + g++ — n8n's dependency `isolated-vm` compiles
  native code via node-gyp.
- Python3 WITH `distutils` for node-gyp: run the install with the
  system paths first (`/usr/bin` early on PATH) — a venv python
  without setuptools fails `ModuleNotFoundError: No module named
  'distutils'` (grounded 2026-11-21).

## 2. Install n8n (user-local, no sudo)

The global npm prefix `/usr/local` is root-owned on this host, so the
install is user-local via the per-command prefix override (no
persistent npm configuration is changed). The version is PINNED so a
re-run is a true no-op instead of a silent upgrade — the pin's SINGLE
SOURCE is `n8n.deps.expected.n8n` in `src/main/resources/application.yml`
(D-1, US-05.3); `install-n8n.sh` extracts it automatically, and the
manager's `n8n upgrade` command performs deliberate upgrades with
stop-first + backups + a health gate (see `docs/DEPENDENCIES.md`). The
manual equivalent (version from the pin):

```bash
npm_config_prefix="$HOME/.local" npm install -g \
  --allow-scripts=@parcel/watcher,isolated-vm,sqlite3,protobufjs,agent-browser,oracledb,msgpackr-extract,ssh2,@sentry/node-native-stacktrace,@sentry/node-cpu-profiler,@confluentinc/kafka-javascript,cpu-features,@scarf/scarf,vue-demi \
  n8n@2.48.7
~/.local/bin/n8n --version
```

- Community edition: n8n ships only the open-source distribution via
  npm; Enterprise features require a separate license and are out of
  scope (see #16). n8n auto-activates its standard FREE community
  license at startup — that is expected Community behavior, not
  Enterprise.
- The binary lands in `~/.local/bin/n8n`, already on PATH.

## 3. Environment file

```bash
mkdir -p ~/.config/n8n
cp docs/install/n8n.env.example ~/.config/n8n/n8n.env
chmod 600 ~/.config/n8n/n8n.env
# edit: fill N8N_API_KEY after step 5
```

`N8N_LISTEN_ADDRESS=127.0.0.1` in that file is what forces the
loopback-only binding (US-05.1).

## 4. systemd service

```bash
mkdir -p ~/.config/systemd/user
cp docs/install/n8n.service ~/.config/systemd/user/n8n.service
systemctl --user daemon-reload
systemctl --user enable --now n8n
systemctl --user status n8n
```

- The unit's `Environment=PATH=...` line puts the user-local Node 24
  ahead of the distro Node 22 — n8n's launcher resolves `env node`,
  and without it the unit crash-loops on the unsupported Node 22.
- Verify loopback-only: `ss -tlnp | grep 5678` must show
  `127.0.0.1:5678`, never `0.0.0.0` or `*:5678`.
- `loginctl enable-linger jllopez` keeps the service alive when not
  logged in interactively (run once).

## 5. Owner setup, API key, MCP activation (one-time, web UI)

With the service running, open http://127.0.0.1:5678 in a browser:

1. First-run setup: create the instance owner account (local email +
   password; credentials are stored in n8n's own encrypted store).
2. Settings → n8n API → create an API key; copy it into
   `~/.config/n8n/n8n.env` as `N8N_API_KEY=...`, then
   `systemctl --user restart n8n`.
3. Settings → Instance-level MCP → Enable; generate an MCP access
   token and store it next to the API key (US-05.4; the manager reads
   it as a separate secret, see US-01.4). NOTE: in the beta this
   toggle is UI-only for self-hosted instances.

## 6. Health verification

```bash
curl -s http://127.0.0.1:5678/healthz
# {"status":"ok"} expected
systemctl --user status n8n   # active (running)
```
