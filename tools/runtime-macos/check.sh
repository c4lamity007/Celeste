#!/usr/bin/env bash
set -euo pipefail

# Runs only on a disposable hosted macOS build runner with public test data.
test "$(uname -s)" = Darwin
test "${GITHUB_ACTIONS:-}" = true
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
export CELESTE_FIXTURE_DIR="$RUNNER_TEMP/celeste-macos-runtime-fixtures"
mkdir -p "$CELESTE_FIXTURE_DIR" tools/runtime-macos/{libs,classes}
curl -fsSL https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar -o tools/runtime-macos/libs/jna.jar
curl -fsSL https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/5.19.0/jna-platform-5.19.0.jar -o tools/runtime-macos/libs/jna-platform.jar
curl -fsSL https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar -o tools/runtime-macos/libs/gson.jar
curl -fsSL https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar -o tools/runtime-macos/libs/fabric-loader.jar
javac -cp 'tools/runtime-macos/libs/*' -d tools/runtime-macos/classes tools/runtime-macos/java/*.java
cargo build --locked --manifest-path tools/runtime-macos/Cargo.toml
fixture_executable="$PWD/tools/runtime-macos/target/debug/celeste-macos-runtime-check"
keychain="$CELESTE_FIXTURE_DIR/fixture.keychain-db"
security create-keychain -p public-disposable-fixture "$keychain"
security default-keychain -d user -s "$keychain"
security list-keychains -d user -s "$keychain"
security unlock-keychain -p public-disposable-fixture "$keychain"
security set-keychain-settings -lut 3600 "$keychain"
# The public 32-zero-byte test key is trusted only for these two executables.
# Production item ACLs are never changed. End users approve Java in Keychain.
security add-generic-password -a a39fbbf8e5c344edb39ee82d -s dev.c4lam.celeste.normal -w AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA -T "$fixture_executable" -T "$JAVA_HOME/bin/java" "$keychain"
"$fixture_executable" seal
java -cp 'tools/runtime-macos/classes:tools/runtime-macos/libs/*' dev.c4lam.celeste.auth.MacRuntimeCheck envelope
"$fixture_executable" open
"$fixture_executable" path > "$CELESTE_FIXTURE_DIR/rust-path.txt"
java -cp 'tools/runtime-macos/classes:tools/runtime-macos/libs/*' dev.c4lam.celeste.auth.MacRuntimeCheck path > "$CELESTE_FIXTURE_DIR/java-path.txt"
cmp "$CELESTE_FIXTURE_DIR/rust-path.txt" "$CELESTE_FIXTURE_DIR/java-path.txt"
native-verifier/target/release/CelesteLauncher --check-hardware > "$CELESTE_FIXTURE_DIR/rust-hardware.json"
java -cp 'tools/runtime-macos/classes:tools/runtime-macos/libs/*' dev.c4lam.celeste.auth.MacRuntimeCheck hardware > "$CELESTE_FIXTURE_DIR/java-hardware.json"
cmp "$CELESTE_FIXTURE_DIR/rust-hardware.json" "$CELESTE_FIXTURE_DIR/java-hardware.json"
printf '%s\n' 'Mac production Rust/Java credential envelopes, Keychain FFI, activation paths, and real hardware profiles agree.'
