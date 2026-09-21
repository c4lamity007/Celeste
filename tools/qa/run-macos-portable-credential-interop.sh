#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This check requires a macOS runner." >&2
  exit 2
fi
if [[ "${GITHUB_ACTIONS:-}" != "true" || "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]]; then
  echo "Refusing to modify Keychain state outside an ephemeral GitHub-hosted runner." >&2
  exit 2
fi
if [[ -z "${RUNNER_TEMP:-}" || ! -d "${RUNNER_TEMP}" ]]; then
  echo "RUNNER_TEMP is missing or invalid." >&2
  exit 2
fi
if [[ ! -x /usr/bin/security ]]; then
  echo "The macOS security tool is unavailable." >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd -P)"
FIXTURE_ROOT="${SCRIPT_DIR}/macos-portable-credential-interop"
WORK_ROOT="$(mktemp -d "${RUNNER_TEMP%/}/celeste-keychain-interop.XXXXXX")"
STATE_FILE="${WORK_ROOT}/original-keychains.json"
DEFAULT_KEYCHAIN_PATH="${WORK_ROOT}/default-fixture.keychain-db"
AUTHORIZED_KEYCHAIN_PATH="${WORK_ROOT}/authorized-fixture.keychain-db"
PLAIN_PATH="${WORK_ROOT}/plain.bin"
ENVELOPE_PATH="${WORK_ROOT}/envelope.bin"
CLASSES_PATH="${WORK_ROOT}/classes"
TARGET_PATH="${WORK_ROOT}/cargo-target"
PREFLIGHT_PATH="${WORK_ROOT}/activation-storage"
KEYCHAIN_PASSWORD=""
SECURITY=/usr/bin/security
STATE_CAPTURED=0
STATE_RESTORED=0

run_bounded() {
  python3 - "$@" <<'PY'
import subprocess
import sys

try:
    subprocess.run(sys.argv[1:], check=True, timeout=30)
except subprocess.TimeoutExpired as error:
    raise SystemExit(f"Synthetic native Keychain probe exceeded 30 seconds: {error.cmd!r}")
PY
}

capture_state() {
  if ! python3 - "${STATE_FILE}" "${SECURITY}" <<'PY'
import json
import pathlib
import shlex
import subprocess
import sys

def values(*arguments):
    output = subprocess.check_output(arguments, text=True)
    return shlex.split(output)

security = sys.argv[2]
default = values(security, "default-keychain", "-d", "user")
search = values(security, "list-keychains", "-d", "user")
if len(default) != 1 or not search:
    raise SystemExit("Could not capture the original user Keychain state")
pathlib.Path(sys.argv[1]).write_text(
    json.dumps({"default": default[0], "search": search}), encoding="utf-8"
)
PY
  then
    return 1
  fi
  STATE_CAPTURED=1
}

restore_state() {
  if ! python3 - "${STATE_FILE}" "${SECURITY}" <<'PY'
import json
import pathlib
import shlex
import subprocess
import sys

state = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
security = sys.argv[2]
subprocess.run(
    [security, "list-keychains", "-d", "user", "-s", *state["search"]],
    check=True,
)
subprocess.run(
    [security, "default-keychain", "-d", "user", "-s", state["default"]],
    check=True,
)

def values(*arguments):
    return shlex.split(subprocess.check_output(arguments, text=True))

if values(security, "default-keychain", "-d", "user") != [state["default"]]:
    raise SystemExit("Default Keychain restoration did not persist")
if values(security, "list-keychains", "-d", "user") != state["search"]:
    raise SystemExit("Keychain search-list restoration did not persist")
PY
  then
    return 1
  fi
  STATE_RESTORED=1
}

cleanup() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT
  set +e
  if [[ "${STATE_CAPTURED}" == 1 ]]; then
    restore_state
    cleanup_status=$?
  fi
  if [[ "${STATE_RESTORED}" == 1 ]]; then
    if [[ -f "${DEFAULT_KEYCHAIN_PATH}" ]]; then
      "${SECURITY}" delete-keychain "${DEFAULT_KEYCHAIN_PATH}" >/dev/null 2>&1 || cleanup_status=1
    fi
    if [[ -f "${AUTHORIZED_KEYCHAIN_PATH}" ]]; then
      "${SECURITY}" delete-keychain "${AUTHORIZED_KEYCHAIN_PATH}" >/dev/null 2>&1 || cleanup_status=1
    fi
  fi
  KEYCHAIN_PASSWORD=""
  if [[ "${STATE_CAPTURED}" == 0 || "${STATE_RESTORED}" == 1 ]]; then
    case "${WORK_ROOT}" in
      "${RUNNER_TEMP%/}"/celeste-keychain-interop.*)
        rm -rf -- "${WORK_ROOT}" || cleanup_status=1
        ;;
      *)
        echo "Refusing to remove an unexpected work directory: ${WORK_ROOT}" >&2
        cleanup_status=1
        ;;
    esac
  else
    echo "Keychain restoration failed; preserving owned evidence at ${WORK_ROOT}" >&2
    cleanup_status=1
  fi
  if [[ ${original_status} -ne 0 ]]; then
    exit "${original_status}"
  fi
  exit "${cleanup_status}"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
KEYCHAIN_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"

# Keep the global Keychain mutation window limited to the actual interoperability
# probe; compilation must neither depend on nor observe the disposable Keychain.
export CARGO_TARGET_DIR="${TARGET_PATH}"
cargo build --locked --quiet --manifest-path "${FIXTURE_ROOT}/Cargo.toml"
mkdir -p "${CLASSES_PATH}"
javac --release 21 -d "${CLASSES_PATH}" \
  "${PROJECT_ROOT}/src/main/java/dev/c4lam/celeste/auth/PortableCredentialProtection.java" \
  "${FIXTURE_ROOT}/MacPortableCredentialConsentBoundaryCheck.java" \
  "${FIXTURE_ROOT}/MacPortableCredentialInteropCheck.java"

capture_state
"${SECURITY}" create-keychain -p "${KEYCHAIN_PASSWORD}" "${DEFAULT_KEYCHAIN_PATH}"
"${SECURITY}" set-keychain-settings -lut 600 "${DEFAULT_KEYCHAIN_PATH}"
"${SECURITY}" unlock-keychain -p "${KEYCHAIN_PASSWORD}" "${DEFAULT_KEYCHAIN_PATH}"
"${SECURITY}" list-keychains -d user -s "${DEFAULT_KEYCHAIN_PATH}"
"${SECURITY}" default-keychain -d user -s "${DEFAULT_KEYCHAIN_PATH}"

run_bounded "${TARGET_PATH}/debug/celeste-macos-keychain-writer" write-default \
  "${PLAIN_PATH}.default" "${ENVELOPE_PATH}.default"
java -cp "${CLASSES_PATH}" \
  dev.c4lam.celeste.auth.MacPortableCredentialConsentBoundaryCheck

# Use a second owned Keychain so the positive fixture creates its ACL atomically;
# changing the default item's owner ACL could itself trigger a consent dialog.
"${SECURITY}" create-keychain -p "${KEYCHAIN_PASSWORD}" "${AUTHORIZED_KEYCHAIN_PATH}"
"${SECURITY}" set-keychain-settings -lut 600 "${AUTHORIZED_KEYCHAIN_PATH}"
"${SECURITY}" unlock-keychain -p "${KEYCHAIN_PASSWORD}" "${AUTHORIZED_KEYCHAIN_PATH}"
"${SECURITY}" list-keychains -d user -s "${AUTHORIZED_KEYCHAIN_PATH}"
"${SECURITY}" default-keychain -d user -s "${AUTHORIZED_KEYCHAIN_PATH}"

run_bounded "${TARGET_PATH}/debug/celeste-macos-keychain-writer" write-authorized \
  "${PLAIN_PATH}" "${ENVELOPE_PATH}" "${PREFLIGHT_PATH}"
java -cp "${CLASSES_PATH}" dev.c4lam.celeste.auth.MacPortableCredentialInteropCheck \
  "${ENVELOPE_PATH}" "${PLAIN_PATH}"

echo "PASS production native-writer/Java-reader Keychain interoperability with explicit synthetic reader authorization"
