#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1090,SC1091
source "$PROJECT_ROOT/core.properties"
# shellcheck disable=SC1091
source "$PROJECT_ROOT/scripts/core-patchset.sh"
: "${CORE_REPOSITORY:?Missing CORE_REPOSITORY}"
: "${CORE_TAG:?Missing CORE_TAG}"
: "${CORE_COMMIT:?Missing CORE_COMMIT}"
: "${CORE_PATCH_FILE:?Missing CORE_PATCH_FILE}"
: "${CORE_PATCH_SHA256:?Missing CORE_PATCH_SHA256}"
: "${ANDROID_WIREGUARD_GO:?Missing ANDROID_WIREGUARD_GO}"
: "${ANDROID_AMNEZIAWG_GO:?Missing ANDROID_AMNEZIAWG_GO}"
: "${GO_VERSION:?Missing GO_VERSION}"
: "${GOMOBILE_VERSION:?Missing GOMOBILE_VERSION}"
: "${ANDROID_NDK_VERSION:?Missing ANDROID_NDK_VERSION}"

BUILD_ROOT="${ZAPRET_CORE_BUILD_ROOT:-$PROJECT_ROOT/core-build}"
SOURCE_DIR="$BUILD_ROOT/source"
OUTPUT_DIR="$BUILD_ROOT/output"
GOPATH_DIR="$BUILD_ROOT/gopath"
LIBS_DIR="$PROJECT_ROOT/app/libs"

for command in git go java javac sha256sum unzip; do
    command -v "$command" >/dev/null || {
        echo "Missing required command: $command" >&2
        exit 1
    }
done

if [[ ! "$CORE_COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
    echo "CORE_COMMIT must be a full lowercase 40-character SHA" >&2
    exit 1
fi
if [[ ! "$CORE_TAG" =~ ^[A-Za-z0-9._+-]+$ ]]; then
    echo "CORE_TAG contains unsupported characters" >&2
    exit 1
fi

GO_ACTUAL="$(go env GOVERSION)"
if [[ "$GO_ACTUAL" != "go$GO_VERSION" ]]; then
    echo "Expected Go $GO_VERSION, got $GO_ACTUAL" >&2
    exit 1
fi

JAVA_ACTUAL="$(java -version 2>&1 | head -n 1)"
if [[ "$JAVA_ACTUAL" != *"17."* ]]; then
    echo "Pinned core builder requires OpenJDK 17, got: $JAVA_ACTUAL" >&2
    exit 1
fi

if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then
    echo "ANDROID_HOME must point to an installed Android SDK" >&2
    exit 1
fi

EXPECTED_NDK="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION"
if [[ ! -f "$EXPECTED_NDK/source.properties" ]]; then
    echo "Missing pinned Android NDK: $EXPECTED_NDK" >&2
    exit 1
fi
NDK_ACTUAL="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$EXPECTED_NDK/source.properties")"
if [[ "$NDK_ACTUAL" != "$ANDROID_NDK_VERSION" ]]; then
    echo "Expected Android NDK $ANDROID_NDK_VERSION, got $NDK_ACTUAL" >&2
    exit 1
fi
export ANDROID_NDK_HOME="$EXPECTED_NDK"
export GOTOOLCHAIN=local
export GOFLAGS=

mkdir -p "$BUILD_ROOT" "$OUTPUT_DIR" "$GOPATH_DIR" "$LIBS_DIR"

if [[ ! -d "$SOURCE_DIR/.git" ]]; then
    if [[ -e "$SOURCE_DIR" ]]; then
        echo "Refusing to replace non-git path: $SOURCE_DIR" >&2
        exit 1
    fi
    git init -q "$SOURCE_DIR"
    git -C "$SOURCE_DIR" remote add origin "$CORE_REPOSITORY"
fi

ORIGIN_URL="$(git -C "$SOURCE_DIR" remote get-url origin 2>/dev/null || true)"
if [[ "$ORIGIN_URL" != "$CORE_REPOSITORY" ]]; then
    echo "Core origin mismatch: expected $CORE_REPOSITORY, got $ORIGIN_URL" >&2
    exit 1
fi

if [[ -n "$(git -C "$SOURCE_DIR" status --porcelain)" ]]; then
    echo "Core checkout is dirty; refusing to overwrite it" >&2
    exit 1
fi

git -C "$SOURCE_DIR" fetch --force --no-tags --depth=1 origin "$CORE_COMMIT"
git -C "$SOURCE_DIR" fetch --force --depth=1 origin "refs/tags/$CORE_TAG:refs/tags/$CORE_TAG"
git -C "$SOURCE_DIR" checkout --detach "$CORE_COMMIT"

ACTUAL_COMMIT="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
if [[ "$ACTUAL_COMMIT" != "$CORE_COMMIT" ]]; then
    echo "Core revision mismatch: $ACTUAL_COMMIT" >&2
    exit 1
fi

TAG_COMMIT="$(git -C "$SOURCE_DIR" rev-parse "${CORE_TAG}^{commit}")"
if [[ "$TAG_COMMIT" != "$CORE_COMMIT" ]]; then
    echo "Core tag mismatch: $CORE_TAG points to $TAG_COMMIT" >&2
    exit 1
fi

PACKAGED_CORE_LICENSE="$PROJECT_ROOT/app/src/main/res/raw/sing_box_extended_license.txt"
if ! cmp -s "$SOURCE_DIR/LICENSE" "$PACKAGED_CORE_LICENSE"; then
    echo "Packaged sing-box-extended license differs from the pinned source" >&2
    exit 1
fi

export GOPATH="$GOPATH_DIR"
export PATH="$GOPATH/bin:$PATH"

pushd "$SOURCE_DIR" >/dev/null

CORE_PATCH_APPLIED=false
cleanup_source_artifacts() {
    find "$SOURCE_DIR" -maxdepth 1 -type f \
        \( -name 'libbox.aar' -o -name 'libbox-legacy.aar' \) -delete
    rm -f "$SOURCE_DIR/dns/transport/fallback/zapret_audit_test.go"
    rm -f "$SOURCE_DIR/route/rule/zapret_performance_test.go"
    if [[ "$CORE_PATCH_APPLIED" == true ]]; then
        reverse_core_patchset "$PROJECT_ROOT" "$SOURCE_DIR"
        CORE_PATCH_APPLIED=false
    fi
}
trap cleanup_source_artifacts EXIT
cleanup_source_artifacts
apply_core_patchset "$PROJECT_ROOT" "$SOURCE_DIR"
CORE_PATCH_APPLIED=true

for expected_module in "$ANDROID_WIREGUARD_GO" "$ANDROID_AMNEZIAWG_GO"; do
    module_path="${expected_module%@*}"
    actual_module="$(go list -m -f '{{.Path}}@{{.Version}}' "$module_path")"
    if [[ "$actual_module" != "$expected_module" ]]; then
        echo "Android WireGuard module mismatch: expected $expected_module, got $actual_module" >&2
        exit 1
    fi
done

hysteria_module_version="${HYSTERIA_CORE_TAG#app/}"
for hysteria_module in github.com/apernet/hysteria/core/v2 github.com/apernet/hysteria/extras/v2; do
    module_json="$(go mod download -json "$hysteria_module@$hysteria_module_version")"
    actual_version="$(jq -r '.Version' <<< "$module_json")"
    actual_commit="$(jq -r '.Origin.Hash' <<< "$module_json")"
    if [[ "$actual_version" != "$hysteria_module_version" || "$actual_commit" != "$HYSTERIA_CORE_COMMIT" ]]; then
        echo "Hysteria module mismatch: $hysteria_module is $actual_version@$actual_commit" >&2
        exit 1
    fi
done

# The host-only verifier does not need naive/Cronet. Android libbox below is still
# built by the pinned upstream builder with its complete Android tag set.
CORE_TAGS="with_gvisor,with_quic,with_wireguard,with_masque,with_mtproxy,with_trusttunnel,with_openvpn,with_sudoku,with_snell,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0"
go build \
    -trimpath \
    -tags "$CORE_TAGS" \
    -ldflags "-X github.com/sagernet/sing-box/constant.Version=${CORE_TAG#v} -X internal/godebug.defaultGODEBUG=multipathtcp=0 -buildid= -checklinkname=0" \
    -o "$OUTPUT_DIR/sing-box" \
    ./cmd/sing-box
chmod 0755 "$OUTPUT_DIR/sing-box"

"$PROJECT_ROOT/scripts/verify-core-version.sh" "$OUTPUT_DIR/sing-box"
"$PROJECT_ROOT/scripts/verify-fixtures.sh" "$OUTPUT_DIR/sing-box"
install -m 0644 \
    "$PROJECT_ROOT/audit/fallback_strategy_test.go" \
    "$SOURCE_DIR/dns/transport/fallback/zapret_audit_test.go"
go test ./dns/transport/fallback -run '^TestZapret' -count=1
rm -f "$SOURCE_DIR/dns/transport/fallback/zapret_audit_test.go"
install -m 0644 \
    "$PROJECT_ROOT/audit/rule_set_performance_test.go" \
    "$SOURCE_DIR/route/rule/zapret_performance_test.go"
ZAPRET_RU_DOMAIN_SRS="$PROJECT_ROOT/app/src/main/assets/rule-sets/zapret-ru-domains.srs" \
ZAPRET_RU_IP_SRS="$PROJECT_ROOT/app/src/main/assets/rule-sets/zapret-ru-ip.srs" \
    go test ./route/rule \
        -run '^TestZapretProductionRuleSetsMatchAndStayBounded$' \
        -bench '^BenchmarkZapretProductionRuleSetLookup$' \
        -benchtime=1s \
        -benchmem \
        -v \
        -count=1 \
        | tee "$OUTPUT_DIR/rule-set-benchmark.txt"
rm -f "$SOURCE_DIR/route/rule/zapret_performance_test.go"
go test ./dns/... ./route/rule ./experimental/libbox
go test -tags "$CORE_TAGS" ./transport/wireguard ./protocol/wireguard
# badlinkname/tfogo_checklinkname0 are host linkname checks and make Go's
# crypto/tls test binary incompatible with the pinned toolchain; protocol
# coverage still uses the complete functional build tag set.
CORE_TEST_TAGS="${CORE_TAGS%,badlinkname,tfogo_checklinkname0}"
go test -tags "$CORE_TEST_TAGS" ./protocol/hysteria2
go test -tags "$CORE_TEST_TAGS" ./parser/link

go install "github.com/sagernet/gomobile/cmd/gomobile@$GOMOBILE_VERSION"
go install "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_VERSION"
go run ./cmd/internal/build_libbox -target android -platform android/arm,android/arm64,android/amd64

install -m 0644 libbox.aar "$LIBS_DIR/libbox.aar"
install -m 0644 libbox.aar "$OUTPUT_DIR/libbox.aar"

unzip -tq "$OUTPUT_DIR/libbox.aar" >/dev/null
mapfile -t LIBBOX_ENTRIES < <(unzip -Z1 "$OUTPUT_DIR/libbox.aar")
if [[ ! " ${LIBBOX_ENTRIES[*]} " == *" classes.jar "* ]]; then
    echo "libbox AAR does not contain classes.jar" >&2
    exit 1
fi
mapfile -t LIBBOX_NATIVE_ENTRIES < <(
    printf '%s\n' "${LIBBOX_ENTRIES[@]}" | grep -E '^jni/[^/]+/libbox\.so$' | sort || true
)
EXPECTED_LIBBOX_NATIVE_ENTRIES=(
    "jni/arm64-v8a/libbox.so"
    "jni/armeabi-v7a/libbox.so"
    "jni/x86_64/libbox.so"
)
if [[ "${LIBBOX_NATIVE_ENTRIES[*]}" != "${EXPECTED_LIBBOX_NATIVE_ENTRIES[*]}" ]]; then
    echo "Unexpected libbox ABI set: ${LIBBOX_NATIVE_ENTRIES[*]:-none}" >&2
    exit 1
fi

"$OUTPUT_DIR/sing-box" version > "$OUTPUT_DIR/core-version.txt"

cat > "$OUTPUT_DIR/core-build-metadata.json" <<EOF
{
  "repository": "$CORE_REPOSITORY",
  "tag": "$CORE_TAG",
  "commit": "$CORE_COMMIT",
  "patch_file": "$CORE_PATCH_FILE",
  "patch_sha256": "$CORE_PATCH_SHA256",
  "hysteria_core_tag": "$HYSTERIA_CORE_TAG",
  "hysteria_core_commit": "$HYSTERIA_CORE_COMMIT",
  "android_wireguard_go": "$ANDROID_WIREGUARD_GO",
  "android_amneziawg_go": "$ANDROID_AMNEZIAWG_GO",
  "go": "$GO_VERSION",
  "gomobile": "$GOMOBILE_VERSION",
  "android_ndk": "$ANDROID_NDK_VERSION",
  "abis": ["arm64-v8a", "armeabi-v7a", "x86_64"]
}
EOF
install -m 0644 "$SOURCE_DIR/LICENSE" "$OUTPUT_DIR/sing-box-extended-LICENSE.txt"

LIBBOX_SHA256="$(sha256sum "$OUTPUT_DIR/libbox.aar" | awk '{print $1}')"
cat > "$LIBS_DIR/libbox.properties" <<EOF
CORE_TAG=$CORE_TAG
CORE_COMMIT=$CORE_COMMIT
CORE_PATCH_FILE=$CORE_PATCH_FILE
CORE_PATCH_SHA256=$CORE_PATCH_SHA256
HYSTERIA_CORE_TAG=$HYSTERIA_CORE_TAG
HYSTERIA_CORE_COMMIT=$HYSTERIA_CORE_COMMIT
ANDROID_WIREGUARD_GO=$ANDROID_WIREGUARD_GO
ANDROID_AMNEZIAWG_GO=$ANDROID_AMNEZIAWG_GO
LIBBOX_SHA256=$LIBBOX_SHA256
EOF

(
    cd "$OUTPUT_DIR"
    sha256sum \
        sing-box \
        libbox.aar \
        core-version.txt \
        core-build-metadata.json \
        rule-set-benchmark.txt \
        sing-box-extended-LICENSE.txt \
        > SHA256SUMS
)

cleanup_source_artifacts
trap - EXIT

if [[ -n "$(git status --porcelain)" ]]; then
    echo "Core build modified its source checkout" >&2
    git status --short >&2
    exit 1
fi

popd >/dev/null

echo "Core artifacts written to $OUTPUT_DIR and $LIBS_DIR"
