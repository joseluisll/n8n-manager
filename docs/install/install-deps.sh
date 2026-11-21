#!/usr/bin/env bash
#
# install-deps.sh — idempotent dependency management for the local stack
# (node, java 21, maven). US-05.5 (#23). Companion of docs/DEPENDENCIES.md
# (the canonical version record) and docs/install/INSTALL.md (the n8n
# install that consumes this baseline).
#
# Usage:
#   install-deps.sh           install/upgrade as needed (no sudo; idempotent)
#   install-deps.sh --check   verify only; exit 1 when a dependency is unsatisfied
#
set -euo pipefail

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

TOOLS_DIR="$HOME/tools"
LOCAL_BIN="$HOME/.local/bin"
NODE_VERSION="24.22.3"          # pinned Node 24 LTS ("Krypton")
NODE_DIST="node-v${NODE_VERSION}-linux-x64"
NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}"
NODE_STABLE_LINK="$TOOLS_DIR/node24"
JAVA21_HOME="/usr/lib/jvm/java-21-openjdk-amd64"

declare -a FAILURES=()

ok()      { printf 'OK    %-6s %s\n' "$1" "$2"; }
fail()    { printf 'FAIL  %-6s %s\n' "$1" "$2"; FAILURES+=("$1"); }
info()    { printf '...   %-6s %s\n' "$1" "$2"; }

node_ok()  { command -v node >/dev/null 2>&1 && [[ "$(node -p 'process.versions.node')" == 24.* ]]; }
java_ok()  { [[ -x "$JAVA21_HOME/bin/java" ]]; }
maven_ok() { command -v mvn >/dev/null 2>&1; }

# --- Node.js + npm ---------------------------------------------------------
if node_ok; then
  ok node "node $(node -p 'process.versions.node') (>= 24 required)"
  ok npm  "npm $(npm --version)"
else
  found="$(command -v node >/dev/null 2>&1 && node --version || echo missing)"
  if [[ "$CHECK_ONLY" == 1 ]]; then
    fail node "found ${found}, expected v${NODE_VERSION%%.*}.x — run: $0 (no --check)"
  else
    info node "installing Node v${NODE_VERSION} LTS to ${TOOLS_DIR}"
    scratch="$(mktemp -d)"
    trap 'rm -rf "$scratch"' EXIT
    curl -fsSLo "$scratch/$NODE_DIST.tar.xz" "$NODE_URL/$NODE_DIST.tar.xz"
    curl -fsSLo "$scratch/SHASUMS256.txt" "$NODE_URL/SHASUMS256.txt"
    (cd "$scratch" && grep "$NODE_DIST.tar.xz" SHASUMS256.txt | sha256sum -c -)
    mkdir -p "$TOOLS_DIR"
    tar -xJf "$scratch/$NODE_DIST.tar.xz" -C "$TOOLS_DIR/"
    ln -sfn "$TOOLS_DIR/$NODE_DIST" "$NODE_STABLE_LINK"
    ln -sfn "$NODE_STABLE_LINK/bin/node" "$LOCAL_BIN/node"
    ln -sfn "$NODE_STABLE_LINK/bin/npm"  "$LOCAL_BIN/npm"
    ln -sfn "$NODE_STABLE_LINK/bin/npx"  "$LOCAL_BIN/npx"
    hash -r
    node_ok && ok node "installed $(node --version) (user-local, distro node untouched)" \
             || fail node "installation did not produce a Node 24 on PATH"
    ok npm "npm $(npm --version) (bundled)"
  fi
fi

# --- Java 21 ---------------------------------------------------------------
if java_ok; then
  ok java "$("$JAVA21_HOME/bin/java" -version 2>&1 | head -1)"
else
  # Idempotent system install exists (apt); never run sudo unattended — instruct.
  fail java "OpenJDK 21 not found at $JAVA21_HOME — run: sudo apt-get install openjdk-21-jdk (idempotent)"
fi

# --- Maven -----------------------------------------------------------------
if maven_ok; then
  ok maven "$(mvn --version | head -1)"
else
  if [[ "$CHECK_ONLY" == 1 ]]; then
    fail maven "not on PATH — install per docs/DEPENDENCIES.md (tarball to ~/tools/maven)"
  else
    info maven "installing Maven 3.9.16 to ${TOOLS_DIR}"
    scratch="$(mktemp -d)"
    trap 'rm -rf "$scratch"' EXIT
    curl -fsSLo "$scratch/maven.tar.gz" \
      "https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz"
    curl -fsSLo "$scratch/maven.sha512" \
      "https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz.sha512"
    echo "$(cat "$scratch/maven.sha512")  $scratch/maven.tar.gz" | sha512sum -c -
    mkdir -p "$TOOLS_DIR"
    tar -xzf "$scratch/maven.tar.gz" -C "$TOOLS_DIR/"
    rm -rf "$TOOLS_DIR/maven"
    ln -sfn "$TOOLS_DIR/apache-maven-3.9.16" "$TOOLS_DIR/maven"
    mkdir -p "$LOCAL_BIN"
    ln -sfn "$TOOLS_DIR/maven/bin/mvn" "$LOCAL_BIN/mvn"
    hash -r
    maven_ok && ok maven "installed $(mvn --version | head -1)" \
             || fail maven "installation did not put mvn on PATH"
  fi
fi

echo
if [[ "${#FAILURES[@]}" -gt 0 ]]; then
  printf 'Unsatisfied dependencies: %s\n' "${FAILURES[*]}"
  printf 'See docs/DEPENDENCIES.md for the canonical versions and procedures.\n'
  exit 1
fi
echo "All dependencies satisfied."
