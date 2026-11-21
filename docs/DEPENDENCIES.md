# Local-stack dependencies (US-05.5)

Canonical record of the versions the local stack — n8n (Community) plus this
manager — needs on this host, and the idempotent procedures to install,
upgrade and verify them. The manager reports the live state against these
expectations with `deps status`.

## Expected versions

| Dependency | Expected        | Purpose                                   |
|------------|-----------------|-------------------------------------------|
| Node.js    | `>= 24.0.0`     | n8n runtime (n8n 2.x refuses Node < 24)   |
| npm        | `>= 10.0.0`     | n8n install/updates (ships with Node 24)  |
| Java       | `>= 21`         | Manager build and runtime (Spring Boot 3.5) |
| Maven      | `>= 3.9.0`      | Manager build (`mvn -B package`)          |

The machine-readable copy of these expectations is
`src/main/resources/application.yml` under `n8n.deps.expected.*`; keep the
two in sync when changing a baseline.

Verify at any time:

```console
$ n8n-manager deps status        # from the built jar, or
$ docs/install/install-deps.sh --check
```

## Host layout (this machine)

- **Node 24 LTS**: `~/tools/node-v<version>-linux-x64`, stable symlink
  `~/tools/node24` → that directory. `~/.local/bin/{node,npm,npx}` symlink
  into `~/tools/node24/bin` — `~/.local/bin` precedes `/usr/bin` on the user
  PATH, so the user default becomes Node 24 while the distro Node (22,
  shared-`libnode` build) at `/usr/bin/node` is left untouched.
- **Java 21**: `/usr/lib/jvm/java-21-openjdk-amd64` (Ubuntu OpenJDK 21).
  The user default `JAVA_HOME` is `~/tools/jdk-17` and is deliberately NOT
  changed (other tooling relies on it); every Maven/`java` invocation for
  this repo exports Java 21 explicitly, same pattern as neis-cli:
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH="$JAVA_HOME/bin:$PATH"`.
- **Maven**: `~/tools/maven` (3.9.x, on PATH via `~/tools/env.sh`).
- **n8n**: `~/.local/lib/node_modules/n8n` (npm user prefix `~/.local`),
  binary `~/.local/bin/n8n`; systemd user unit
  `~/.config/systemd/user/n8n.service` runs it with the Node 24 runtime
  first on its PATH (see `docs/install/n8n.service`).

## Install / upgrade (idempotent)

`docs/install/install-deps.sh` provisions what it can without sudo and is a
no-op when everything already matches:

```console
$ docs/install/install-deps.sh            # install/upgrade as needed
$ docs/install/install-deps.sh --check    # verify only, exit 1 on mismatch
```

What it does per dependency:

- **Node/npm**: checks `node --version` against `>= 24.0.0` and `npm --version`
  against `>= 10.0.0` (same gate as `deps status`). When Node is unsatisfied,
  downloads the pinned Node 24 LTS tarball from nodejs.org, verifies its
  SHA-256 against the published `SHASUMS256.txt`, extracts to
  `~/tools/node-v<version>-linux-x64`, re-points `~/tools/node24`, and
  refreshes the `~/.local/bin/{node,npm,npx}` symlinks. Re-running with the
  expected version already in place changes nothing.
- **Java 21**: verified in-place (already satisfied on this host via the
  Ubuntu `openjdk-21-jdk` package). When missing, the script prints the exact
  idempotent install command (`sudo apt-get install openjdk-21-jdk` — apt is
  idempotent) instead of running sudo unattended.
- **Maven**: verified at `~/tools/maven` / on PATH. When missing, the script
  prints the documented tarball install steps (download + SHA-512 check +
  extract to `~/tools/maven`, add `~/tools/maven/bin` to PATH via
  `~/tools/env.sh`).

## Diagnostics

`deps status` reports, per dependency: `OK`, `MISSING` (not found on PATH) or
`WRONG VERSION`, each failure with a remediation line naming the fix (the
installer script or the manual step above). Exit status of the `--check` mode
of `install-deps.sh` is non-zero when any dependency is unsatisfied, so CI or
scripts can gate on it.

Java is probed at the JDK 21 installation (`n8n.deps.java-home`, default
`/usr/lib/jvm/java-21-openjdk-amd64`) — the same path `install-deps.sh`
verifies — not at the `java` on PATH: the user-default `JAVA_HOME` here is a
Java 17 that other tooling relies on, and the manager jar is class-file 65, so
a PATH probe would report a WRONG VERSION that does not reflect the manager's
actual runtime. When the PATH `java` is older than the probed JDK 21, both
`deps status` and `install-deps.sh` print a note telling you to export
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH="$JAVA_HOME/bin:$PATH"`
(the documented repo pattern above) before running the manager or Maven.

## Known pitfalls

- **Node ABI**: native npm modules (e.g. n8n's `isolated-vm`) are compiled
  against the Node ABI they were built with (`NODE_MODULE_VERSION`). After a
  Node major upgrade, user-global packages must be reinstalled under the new
  runtime — the n8n install procedure in `docs/install/INSTALL.md` does this.
- **node-gyp needs a `python3` with `distutils`** plus `make`/`g++` — on this
  host use `/usr/bin/python3` (a venv python without setuptools fails with
  `ModuleNotFoundError: No module named 'distutils'`).
- **npm >= 11 blocks install-scripts by default**: a plain
  `npm install -g n8n` prints `npm warn install-scripts ...` and silently
  skips native postinstall builds. Pass the allow-list on the install
  invocation (never a persistent `npm config set`):
  `npm install -g --allow-scripts=@parcel/watcher,isolated-vm,sqlite3,protobufjs,agent-browser,oracledb,msgpackr-extract,ssh2,@sentry/node-native-stacktrace,@sentry/node-cpu-profiler,@confluentinc/kafka-javascript,cpu-features,@scarf/scarf,vue-demi n8n@<version>`.
- **Distro Node is a shared-`libnode` build** — never link n8n's native
  modules against it. The `~/.local/lib/node-libs` LIBRARY_PATH workaround
  from the first install attempt (a symlink set plus the `libnghttp3.a`/
  `libngtcp2.a` placeholder archives) was retired and deleted from this host
  on 2026-11-21 (see REQ issue joseluisll/n8n-manager#24); the Node 24
  tarball is self-contained.
