#!/usr/bin/env bash
#
# install-n8n.sh — idempotent, user-local installation of the single n8n
# Community edition instance on this host (US-05.1, issue #16).
#
# Owner decisions recorded in INSTALL.md (2026-11-21): install method is
# npm — Docker is NOT used for any component; Community edition only.
#
# Re-running this script converges to the same state: every step checks
# for its target state first and does nothing when it is already met.
# Secret values (N8N_API_KEY) are never overwritten by this script.
#
# Scope note: the Node.js runtime itself is NOT provisioned here — it is
# US-05.5's responsibility (docs/install/install-deps.sh, Node pinned in
# docs/DEPENDENCIES.md). This script only verifies the prerequisite.
set -euo pipefail

# Pinned n8n version (decision 2026-11-21): an exact version makes a
# re-run a true no-op instead of a silent upgrade. Bump deliberately
# (update INSTALL.md and docs/DEPENDENCIES.md together).
N8N_VERSION="2.48.7"            # Community edition, via npm

# Native postinstalls npm >= 11 skips without an explicit allow-list
# (pitfall recorded in docs/DEPENDENCIES.md, grounded 2026-11-21).
NPM_ALLOW_SCRIPTS="@parcel/watcher,isolated-vm,sqlite3,protobufjs,agent-browser,oracledb,msgpackr-extract,ssh2,@sentry/node-native-stacktrace,@sentry/node-cpu-profiler,@confluentinc/kafka-javascript,cpu-features,@scarf/scarf,vue-demi"

N8N_PREFIX="$HOME/.local"
N8N_BIN="$N8N_PREFIX/bin/n8n"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$HOME/.config/n8n/n8n.env"
UNIT_DST="$HOME/.config/systemd/user/n8n.service"

step() { printf '\n== %s ==\n' "$*"; }

step "0/5 prerequisites (Node >= 24 via US-05.5, npm >= 11, build tools)"
node_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
if [ "$node_major" -lt 24 ]; then
  echo "ERROR: Node.js >= 24 required, found '$(node -v 2>/dev/null || echo none)'." >&2
  echo "Run docs/install/install-deps.sh first (US-05.5 owns the Node runtime)." >&2
  exit 1
fi
echo "node: $(node -v) ($(command -v node))"
npm_v="$(npm -v)"
echo "npm:  $npm_v"
for tool in make g++; do
  command -v "$tool" >/dev/null || { echo "ERROR: $tool missing (isolated-vm needs it)" >&2; exit 1; }
done
echo "make + g++ present"

step "1/5 n8n ${N8N_VERSION} (Community edition) via npm under prefix ${N8N_PREFIX}"
if [ -x "$N8N_BIN" ] && [ "$("$N8N_BIN" --version)" = "$N8N_VERSION" ]; then
  echo "already installed: n8n $N8N_VERSION at $N8N_BIN"
else
  # A re-run of `npm install -g` re-resolves the whole tree even for an
  # already-satisfied spec (minutes of work, files churned under a live
  # service), so it only runs when the pinned version is NOT met.
  npm_config_prefix="$N8N_PREFIX" npm install -g --allow-scripts="$NPM_ALLOW_SCRIPTS" "n8n@$N8N_VERSION"
fi
echo "installed n8n version: $("$N8N_BIN" --version)"

step "2/5 Environment file (loopback binding + secrets)"
mkdir -p "$HOME/.config/n8n"
if [ -f "$ENV_FILE" ]; then
  chmod 600 "$ENV_FILE"
  echo "already present, left untouched (may hold secrets): $ENV_FILE"
else
  install -m 600 "$SCRIPT_DIR/n8n.env.example" "$ENV_FILE"
  echo "created from n8n.env.example — edit it to set N8N_API_KEY (step 4 of INSTALL.md)"
fi

step "3/5 systemd user unit"
mkdir -p "$(dirname "$UNIT_DST")"
if cmp -s "$SCRIPT_DIR/n8n.service" "$UNIT_DST"; then
  echo "unit already up to date: $UNIT_DST"
else
  cp "$SCRIPT_DIR/n8n.service" "$UNIT_DST"
  systemctl --user daemon-reload
  echo "unit installed/updated: $UNIT_DST"
fi
systemctl --user enable --now n8n

step "4/5 linger (service survives logout)"
if loginctl show-user "$(id -un)" --property=Linger 2>/dev/null | grep -q '=yes'; then
  echo "linger already enabled"
else
  loginctl enable-linger "$(id -un)"
  echo "linger enabled"
fi

step "5/5 verification (loopback-only + health)"
ok=""
for _ in $(seq 1 45); do
  if systemctl --user is-active --quiet n8n 2>/dev/null; then
    addrs="$(ss -tln 2>/dev/null | awk '$4 ~ /:5678$/ {print $4}')"
    if [ -n "$addrs" ]; then ok=1; break; fi
  fi
  sleep 2
done
if [ -z "$ok" ]; then
  echo "ERROR: n8n.service is not listening on 5678 after 90s:" >&2
  systemctl --user status n8n --no-pager || true
  exit 1
fi
bad="$(printf '%s\n' "$addrs" | grep -v '^127\.0\.0\.1:5678$' || true)"
if [ -n "$bad" ]; then
  echo "ERROR: port 5678 is NOT loopback-only, also bound on:" >&2
  printf '%s\n' "$bad" >&2
  exit 1
fi
echo "listening on 127.0.0.1:5678 only"
printf 'health: '
curl -fsS http://127.0.0.1:5678/healthz
echo
echo "ALL STEPS CONVERGED"
