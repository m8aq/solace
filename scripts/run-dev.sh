#!/usr/bin/env bash
#
# Launches the Solace dev client with the loopback control API and full-layer hot reload.
#
# Save any file under api/, bindings/, loader/, ui/, hub/, common/ or plugins/ and the layer
# rebuilds and reloads on its own - no second terminal, no restart. The rebuild loop runs in the
# background for the life of the client; --no-watch opts out.
#
# Usage: scripts/run-dev.sh [options]
#
#   --port N            Control API port (default 7780; 0 picks a free one)
#   --reload-port N     Layer reload endpoint port (default: --port + 1)
#   --no-reload         Launch flat, without the reloadable classloader
#   --no-watch          Don't rebuild on save; reload then needs a manual `gradlew layerJars`
#   --account-file FILE  Credentials file, mode 0600 (see --help for the format)
#   --no-build          Skip the Gradle build and launch what is already compiled
#   --token             Require an X-Solace-Token header (off by default)
#   --jvm-arg ARG       Extra JVM argument, repeatable
#   --dry-run           Print the command instead of running it
#
# Credentials are read from a file, never from argv: anything on the command line is visible to
# every process on the machine via `ps`. The file is one KEY=VALUE per line:
#
#   username=you@example.com
#   password=hunter2
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PORT=7780
RELOAD_PORT=""
ACCOUNT_FILE=""
BUILD=true
DRY_RUN=false
REQUIRE_TOKEN=false
RELOADABLE=true
WATCH=true
JVM_ARGS=()

usage() {
    sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --port)         PORT="$2"; shift 2 ;;
        --reload-port)  RELOAD_PORT="$2"; shift 2 ;;
        --no-reload)    RELOADABLE=false; shift ;;
        --no-watch)     WATCH=false; shift ;;
        --account-file) ACCOUNT_FILE="$2"; shift 2 ;;
        --no-build)     BUILD=false; shift ;;
        --token)        REQUIRE_TOKEN=true; shift ;;
        --jvm-arg)      JVM_ARGS+=("$2"); shift 2 ;;
        --dry-run)      DRY_RUN=true; shift ;;
        -h|--help)      usage ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

SOLACE_ARGS=""
if [[ -n "${ACCOUNT_FILE}" ]]; then
    [[ -f "${ACCOUNT_FILE}" ]] || { echo "no such file: ${ACCOUNT_FILE}" >&2; exit 1; }

    # Refuse a world-readable credentials file rather than silently using it.
    PERMS="$(stat -f '%Lp' "${ACCOUNT_FILE}" 2>/dev/null || stat -c '%a' "${ACCOUNT_FILE}")"
    if [[ "${PERMS}" != "600" ]]; then
        echo "${ACCOUNT_FILE} is mode ${PERMS}; run: chmod 600 ${ACCOUNT_FILE}" >&2
        exit 1
    fi

    USERNAME="$(sed -n 's/^username=//p' "${ACCOUNT_FILE}" | head -n1)"
    PASSWORD="$(sed -n 's/^password=//p' "${ACCOUNT_FILE}" | head -n1)"
    ACCOUNT="username=${USERNAME}:password=${PASSWORD}"
    SOLACE_ARGS="--account;${ACCOUNT}"
fi

cd "${PROJECT_ROOT}"

: "${RELOAD_PORT:=$((PORT + 1))}"

# :devboot runs Solace in a child classloader so the whole layer - api, sdk, bindings, loader, ui -
# can be reloaded without restarting the client or logging in again. --no-reload falls back to the
# flat single-classloader launch.
if [[ "${RELOADABLE}" == true ]]; then
    GRADLE_ARGS=(:devboot:runDev "-PcontrolApiPort=${PORT}" "-PreloadPort=${RELOAD_PORT}")
else
    GRADLE_ARGS=(:loader:runDev "-PcontrolApiPort=${PORT}")
fi
[[ "${REQUIRE_TOKEN}" == true ]] && GRADLE_ARGS+=("-PcontrolApiToken=true")

# -P rather than -D: runDev forks its own JVM, so a -D on the Gradle command line configures the
# daemon and never reaches the client. build.gradle.kts maps these onto system properties for the
# forked process only, which also keeps the password out of the environment children would inherit.
[[ -n "${SOLACE_ARGS}" ]] && GRADLE_ARGS+=("-PsolaceArgs=${SOLACE_ARGS}")

for arg in "${JVM_ARGS[@]:-}"; do
    [[ -n "${arg}" ]] && GRADLE_ARGS+=("-Dorg.gradle.jvmargs=${arg}")
done

if [[ "${DRY_RUN}" == true ]]; then
    # Redacted: SOLACE_ARGS carries the password.
    printf './gradlew'
    for arg in "${GRADLE_ARGS[@]}"; do
        [[ "${arg}" == -PsolaceArgs=* ]] && printf ' -PsolaceArgs=<redacted>' || printf ' %s' "${arg}"
    done
    printf '\n'
    exit 0
fi

if [[ "${BUILD}" == true ]]; then
    if [[ "${RELOADABLE}" == true ]]; then
        ./gradlew layerJars
    else
        ./gradlew :devplugins:jar :loader:classes
    fi
fi

# The rebuild half of hot reload. LayerWatcher inside the client polls the layer jars, but nothing
# rewrites them on its own - without this loop a saved file changes nothing on disk and the client
# correctly does nothing, which reads as "hot reload is broken". Gradle takes no lock that would
# block the runDev build alongside it, so the two coexist in one terminal.
WATCH_LOG="${PROJECT_ROOT}/build/run-dev-watch.log"
WATCH_PID=""
if [[ "${WATCH}" == true && "${RELOADABLE}" == true ]]; then
    mkdir -p "${PROJECT_ROOT}/build"
    ./gradlew -t layerJars > "${WATCH_LOG}" 2>&1 &
    WATCH_PID=$!
    # Not exec'ing below, so this trap actually runs: an orphaned continuous build would keep a
    # Gradle daemon busy rebuilding for a client that is long gone.
    trap '[[ -n "${WATCH_PID}" ]] && kill "${WATCH_PID}" 2>/dev/null || true' EXIT INT TERM
fi

cat <<EOF

Control API:   http://127.0.0.1:${PORT}      (endpoint file ~/.solace/controlapi/<pid>.json)
Try:           curl -s localhost:${PORT}/api/status | jq
EOF

if [[ "${RELOADABLE}" == true ]]; then
cat <<EOF
Layer reload:  http://127.0.0.1:${RELOAD_PORT}/status   (POST /reload)
EOF
    if [[ -n "${WATCH_PID}" ]]; then
cat <<EOF
Rebuild loop:  running (pid ${WATCH_PID}), log at ${WATCH_LOG}
               Save any layer source and watch for '[layer] generation N up' below.
               Do NOT run ./gradlew in another terminal while this is up - two builds
               writing the same jars produces silently empty ones. Use --no-watch if
               you need to build by hand.
EOF
    else
cat <<EOF
Rebuild loop:  off (--no-watch). Reload with: ./gradlew layerJars
EOF
    fi
fi
echo

./gradlew "${GRADLE_ARGS[@]}"
