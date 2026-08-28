#!/usr/bin/env bash
set -euo pipefail

# Offline tests for check-libbox-fingerprint.sh and ensure-libbox.sh. Every
# scenario runs in a throwaway sandbox project tree with a fake toolchain and a
# fake build-core.sh; the real app/libs and core-build are never touched.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

write_fingerprint() {
    local root="$1"
    (
        # shellcheck disable=SC1091
        source "$root/core.properties"
        cat > "$root/app/libs/libbox.properties" <<EOF
CORE_TAG=$CORE_TAG
CORE_COMMIT=$CORE_COMMIT
CORE_PATCH_FILE=$CORE_PATCH_FILE
CORE_PATCH_SHA256=$CORE_PATCH_SHA256
HYSTERIA_CORE_TAG=$HYSTERIA_CORE_TAG
HYSTERIA_CORE_COMMIT=$HYSTERIA_CORE_COMMIT
ANDROID_WIREGUARD_GO=$ANDROID_WIREGUARD_GO
ANDROID_AMNEZIAWG_GO=$ANDROID_AMNEZIAWG_GO
LIBBOX_SHA256=$(sha256sum "$root/app/libs/libbox.aar" | awk '{print $1}')
EOF
    )
}

make_sandbox() {
    local root="$1"
    mkdir -p "$root/scripts" "$root/core-patches" "$root/app/libs"
    cp "$SCRIPT_DIR/core-patchset.sh" \
        "$SCRIPT_DIR/check-libbox-fingerprint.sh" \
        "$SCRIPT_DIR/ensure-libbox.sh" \
        "$root/scripts/"
    printf 'fake patch\n' > "$root/core-patches/0001-test.patch"
    (cd "$root" && sha256sum core-patches/0001-test.patch > core-patches/series.sha256)
    local manifest_sha
    manifest_sha="$(sha256sum "$root/core-patches/series.sha256" | awk '{print $1}')"
    cat > "$root/core.properties" <<EOF
CORE_REPOSITORY=https://example.invalid/core.git
CORE_TAG=v1.0.0-test
CORE_COMMIT=1111111111111111111111111111111111111111
CORE_PATCH_FILE=core-patches/series.sha256
CORE_PATCH_SHA256=$manifest_sha
HYSTERIA_CORE_TAG=app/v2.12.2
HYSTERIA_CORE_COMMIT=619a6f856b69fb7ee6a7a379e810e68b84004605
ANDROID_WIREGUARD_GO=github.com/example/wireguard-go@v0.0.1
ANDROID_AMNEZIAWG_GO=github.com/example/amneziawg-go/v3@v3.0.1
GO_VERSION=1.26.4
GOMOBILE_VERSION=v0.1.12
ANDROID_NDK_VERSION=28.0.13004108
ANDROID_COMPILE_SDK=36
ANDROID_BUILD_TOOLS=36.0.0
EOF
    printf 'fake aar payload\n' > "$root/app/libs/libbox.aar"
    write_fingerprint "$root"
    cat > "$root/scripts/build-core.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/core.properties"
printf 'rebuilt aar payload\n' > "$ROOT/app/libs/libbox.aar"
cat > "$ROOT/app/libs/libbox.properties" <<FINGERPRINT
CORE_TAG=$CORE_TAG
CORE_COMMIT=$CORE_COMMIT
CORE_PATCH_FILE=$CORE_PATCH_FILE
CORE_PATCH_SHA256=$CORE_PATCH_SHA256
HYSTERIA_CORE_TAG=$HYSTERIA_CORE_TAG
HYSTERIA_CORE_COMMIT=$HYSTERIA_CORE_COMMIT
ANDROID_WIREGUARD_GO=$ANDROID_WIREGUARD_GO
ANDROID_AMNEZIAWG_GO=$ANDROID_AMNEZIAWG_GO
LIBBOX_SHA256=$(sha256sum "$ROOT/app/libs/libbox.aar" | awk '{print $1}')
FINGERPRINT
touch "$ROOT/build-core-invoked"
EOF
    chmod +x "$root"/scripts/*.sh
}

# Fake pinned toolchain so ensure-libbox.sh probes are deterministic. The go
# and java shims report the pinned versions; ANDROID_HOME points at a stub SDK
# with the pinned NDK directory.
make_toolchain() {
    local root="$1"
    local go_version="$2"
    mkdir -p "$root/bin" "$root/sdk/ndk/28.0.13004108"
    printf 'Pkg.Revision = 28.0.13004108\n' > "$root/sdk/ndk/28.0.13004108/source.properties"
    cat > "$root/bin/go" <<EOF
#!/usr/bin/env bash
if [[ "\${1:-} \${2:-}" == "env GOVERSION" ]]; then
    echo "go$go_version"
fi
EOF
    cat > "$root/bin/java" <<'EOF'
#!/usr/bin/env bash
echo 'openjdk version "17.0.0" 2026-01-01' >&2
EOF
    printf '#!/usr/bin/env bash\nexit 0\n' > "$root/bin/javac"
    chmod +x "$root/bin/go" "$root/bin/java" "$root/bin/javac"
}

expect_check() {
    local root="$1" expected="$2" label="$3"
    local status=0 output
    output="$("$root/scripts/check-libbox-fingerprint.sh" "$root" 2>&1)" || status=$?
    if [[ "$status" -ne "$expected" ]]; then
        printf '%s\n' "$output" >&2
        fail "$label: expected exit $expected, got $status"
    fi
    echo "PASS: $label"
}

expect_ensure() {
    local root="$1" expected="$2" label="$3"
    shift 3
    local status=0 output
    output="$(env "$@" "$root/scripts/ensure-libbox.sh" "$root" 2>&1)" || status=$?
    if [[ "$status" -ne "$expected" ]]; then
        printf '%s\n' "$output" >&2
        fail "$label: expected exit $expected, got $status"
    fi
    echo "PASS: $label"
}

# --- check-libbox-fingerprint.sh ---

SANDBOX="$WORK_DIR/fresh"
make_sandbox "$SANDBOX"
expect_check "$SANDBOX" 0 "check: current AAR passes"

SANDBOX="$WORK_DIR/stale-commit"
make_sandbox "$SANDBOX"
sed -i 's/^CORE_COMMIT=.*/CORE_COMMIT=2222222222222222222222222222222222222222/' \
    "$SANDBOX/core.properties"
expect_check "$SANDBOX" 2 "check: AAR built from an old commit is stale"

SANDBOX="$WORK_DIR/stale-module"
make_sandbox "$SANDBOX"
sed -i 's/^ANDROID_AMNEZIAWG_GO=.*/ANDROID_AMNEZIAWG_GO=github.com\/example\/amneziawg-go\/v3@v3.0.2/' \
    "$SANDBOX/core.properties"
expect_check "$SANDBOX" 2 "check: AAR built with an old Go module is stale"

SANDBOX="$WORK_DIR/missing-aar"
make_sandbox "$SANDBOX"
rm "$SANDBOX/app/libs/libbox.aar"
expect_check "$SANDBOX" 2 "check: missing AAR is stale"

SANDBOX="$WORK_DIR/tampered-aar"
make_sandbox "$SANDBOX"
printf 'tampered\n' >> "$SANDBOX/app/libs/libbox.aar"
expect_check "$SANDBOX" 2 "check: AAR content not matching its fingerprint is stale"

SANDBOX="$WORK_DIR/desynced-patches"
make_sandbox "$SANDBOX"
printf 'edited patch\n' >> "$SANDBOX/core-patches/0001-test.patch"
(cd "$SANDBOX" && sha256sum core-patches/0001-test.patch > core-patches/series.sha256)
expect_check "$SANDBOX" 3 "check: patch manifest desynced from core.properties pin"

# --- ensure-libbox.sh ---

SANDBOX="$WORK_DIR/ensure-fresh"
make_sandbox "$SANDBOX"
make_toolchain "$WORK_DIR/toolchain-ok" 1.26.4
expect_ensure "$SANDBOX" 0 "ensure: current AAR is a no-op" \
    PATH="$WORK_DIR/toolchain-ok/bin:$PATH" ANDROID_HOME="$WORK_DIR/toolchain-ok/sdk"
[[ ! -f "$SANDBOX/build-core-invoked" ]] || fail "ensure: no-op must not rebuild the core"

SANDBOX="$WORK_DIR/ensure-rebuild"
make_sandbox "$SANDBOX"
rm "$SANDBOX/app/libs/libbox.aar"
expect_ensure "$SANDBOX" 0 "ensure: stale AAR is rebuilt when the toolchain is pinned" \
    PATH="$WORK_DIR/toolchain-ok/bin:$PATH" ANDROID_HOME="$WORK_DIR/toolchain-ok/sdk"
[[ -f "$SANDBOX/build-core-invoked" ]] || fail "ensure: rebuild must invoke build-core.sh"
expect_check "$SANDBOX" 0 "ensure: rebuilt AAR matches the pins"

SANDBOX="$WORK_DIR/ensure-bad-toolchain"
make_sandbox "$SANDBOX"
rm "$SANDBOX/app/libs/libbox.aar"
make_toolchain "$WORK_DIR/toolchain-bad" 1.20.0
expect_ensure "$SANDBOX" 2 "ensure: stale AAR without the pinned toolchain fails clearly" \
    PATH="$WORK_DIR/toolchain-bad/bin:$PATH" ANDROID_HOME="$WORK_DIR/toolchain-bad/sdk"
[[ ! -f "$SANDBOX/build-core-invoked" ]] || fail "ensure: broken toolchain must not rebuild"

SANDBOX="$WORK_DIR/ensure-autobuild-off"
make_sandbox "$SANDBOX"
rm "$SANDBOX/app/libs/libbox.aar"
expect_ensure "$SANDBOX" 2 "ensure: ZAPRET_CORE_AUTOBUILD=0 fails instead of rebuilding" \
    PATH="$WORK_DIR/toolchain-ok/bin:$PATH" ANDROID_HOME="$WORK_DIR/toolchain-ok/sdk" \
    ZAPRET_CORE_AUTOBUILD=0
[[ ! -f "$SANDBOX/build-core-invoked" ]] || fail "ensure: disabled autobuild must not rebuild"

SANDBOX="$WORK_DIR/ensure-desynced"
make_sandbox "$SANDBOX"
printf 'edited patch\n' >> "$SANDBOX/core-patches/0001-test.patch"
(cd "$SANDBOX" && sha256sum core-patches/0001-test.patch > core-patches/series.sha256)
expect_ensure "$SANDBOX" 3 "ensure: desynced pins fail without a rebuild attempt" \
    PATH="$WORK_DIR/toolchain-ok/bin:$PATH" ANDROID_HOME="$WORK_DIR/toolchain-ok/sdk"
[[ ! -f "$SANDBOX/build-core-invoked" ]] || fail "ensure: desynced pins must not rebuild"

echo "libbox fingerprint checks passed."
