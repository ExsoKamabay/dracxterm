# Shared helpers for the prebuilt build recipes. Sourced, not executed.
# shellcheck shell=bash

set -euo pipefail

PREBUILTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$PREBUILTS_DIR/.." && pwd)"
WORK_DIR="${PREBUILTS_WORK:-$PREBUILTS_DIR/work}"
DL_DIR="$WORK_DIR/downloads"
OUT_DIR="$WORK_DIR/out"

# shellcheck source=../manifest.env
. "$PREBUILTS_DIR/manifest.env"

die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
note() { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33mwarn:\033[0m %s\n' "$*" >&2; }

# --- toolchain ---------------------------------------------------------------

# Locates the NDK pinned in manifest.env. Deliberately refuses to fall back to
# "whatever NDK is around": the whole point of this directory is that the binary
# is a function of the recipe, not of the machine.
setup_toolchain() {
    local ndk_root
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        ndk_root="$ANDROID_NDK_HOME"
    else
        local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
        ndk_root="$sdk/ndk/$NDK_VERSION"
    fi
    [ -d "$ndk_root" ] || die "NDK $NDK_VERSION not found at $ndk_root. Install it:
    sdkmanager \"ndk;$NDK_VERSION\"
  or point ANDROID_NDK_HOME at it."

    local host_tag
    case "$(uname -s)" in
        Linux)  host_tag=linux-x86_64 ;;
        Darwin) host_tag=darwin-x86_64 ;;
        *)      die "unsupported build host: $(uname -s)" ;;
    esac

    TOOLCHAIN="$ndk_root/toolchains/llvm/prebuilt/$host_tag"
    [ -d "$TOOLCHAIN" ] || die "no llvm toolchain at $TOOLCHAIN"

    export NDK_ROOT="$ndk_root"
    export TOOLCHAIN
    export SYSROOT="$TOOLCHAIN/sysroot"
    export CC="$TOOLCHAIN/bin/${TRIPLE}${ANDROID_API}-clang"
    export CXX="$TOOLCHAIN/bin/${TRIPLE}${ANDROID_API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    export READELF="$TOOLCHAIN/bin/llvm-readelf"
    export LD="$TOOLCHAIN/bin/ld.lld"

    [ -x "$CC" ] || die "no compiler at $CC (API $ANDROID_API missing from this NDK?)"

    # Reproducibility flags, applied by every recipe.
    #
    # Without these the output depends on where it was built: clang records the
    # absolute source path in __FILE__ expansions, in assertion strings, and in
    # DW_AT_comp_dir. Two builds of identical sources from two different
    # directories then produce different bytes, which was observed here —
    # libtalloc.so came out 37960 bytes from one work directory and 37896 from
    # another, purely because the paths differed in length.
    #
    #   -ffile-prefix-map  rewrites the recorded paths to a fixed stand-in
    #   -no-canonical-prefixes  stops clang resolving its own path to a realpath
    #   ZERO_AR_DATE / -Wl,--build-id=none  keep timestamps and build IDs out
    #
    # This does not make the build bit-for-bit reproducible on its own — BusyBox
    # embeds its own configuration and build metadata — but it removes the
    # largest and least obvious source of drift.
    export REPRO_CFLAGS="-ffile-prefix-map=$WORK_DIR=/build -no-canonical-prefixes"
    export ZERO_AR_DATE=1

    note "NDK      $NDK_ROOT"
    note "target   ${TRIPLE}${ANDROID_API} ($ANDROID_ABI)"
    note "compiler $("$CC" --version | head -1)"
}

# --- fetching ----------------------------------------------------------------

# fetch_tarball <url> <sha256> <filename>
# Refuses to proceed on a digest mismatch. A cached file that fails the check is
# deleted rather than reused, so a truncated download self-heals on the next run.
fetch_tarball() {
    local url="$1" want="$2" name="$3" path="$DL_DIR/$3"
    mkdir -p "$DL_DIR"
    if [ -f "$path" ]; then
        local have; have=$(sha256sum "$path" | cut -d' ' -f1)
        if [ "$have" = "$want" ]; then
            note "cached  $name"
            return 0
        fi
        warn "cached $name has the wrong digest; refetching"
        rm -f "$path"
    fi
    note "fetch   $url"
    curl -fsSL --retry 3 -o "$path" "$url" || die "download failed: $url"
    local have; have=$(sha256sum "$path" | cut -d' ' -f1)
    if [ "$have" != "$want" ]; then
        rm -f "$path"
        die "SHA-256 mismatch for $name
    expected $want
    got      $have
  The pin in manifest.env and upstream disagree. Do not 'fix' this by pasting
  the new digest in without finding out why it changed."
    fi
    note "verified $name"
}

# fetch_git <url> <commit> <dirname>
# The commit SHA is the integrity check: git verifies object hashes itself.
fetch_git() {
    local url="$1" commit="$2" name="$3" path="$DL_DIR/$3"
    mkdir -p "$DL_DIR"
    local at_commit=0
    if [ -d "$path/.git" ]; then
        local have; have=$(git -C "$path" rev-parse HEAD)
        case "$have" in
            "$commit"*) at_commit=1 ;;
        esac
    fi
    if [ "$at_commit" -eq 1 ]; then
        note "cached  $name @ ${commit}"
    else
        if [ -d "$path/.git" ]; then
            note "updating $name"
            git -C "$path" fetch --quiet --tags origin || die "fetch failed: $url"
        else
            note "clone   $url"
            git clone --quiet "$url" "$path" || die "clone failed: $url"
        fi
        git -C "$path" checkout --quiet --detach "$commit" \
            || die "commit $commit not found in $url"
    fi
    # Always, never only on a fresh clone. A cached checkout that was interrupted
    # before its submodules landed leaves dangling symlinks into an empty
    # directory, and the compiler's "no such file" is a long way from the cause.
    # Submodule revisions are recorded in the parent commit, so this stays pinned:
    # checking out $commit fixes exactly which submodule commits get used.
    if [ -f "$path/.gitmodules" ]; then
        git -C "$path" submodule update --init --recursive --quiet \
            || die "submodule checkout failed in $name"
    fi
    note "verified $name @ $(git -C "$path" rev-parse --short HEAD)"
}

# --- output ------------------------------------------------------------------

# install_artifact <built-file> <lib-name.so>
# Everything the app ships lands in jniLibs as lib*.so, even the plain
# executables: that is the only way Android extracts them into nativeLibraryDir
# as real files that can be exec()'d on API 29+.
install_artifact() {
    # Separate statements on purpose: `local a=1 b="$a"` declares every name first
    # and only then assigns, so `$a` is still unset when `b` is evaluated — which
    # under `set -u` aborts the build with an unbound-variable error.
    local src="$1" dest_name="$2"
    local dest="$OUT_DIR/$dest_name"
    mkdir -p "$OUT_DIR"
    cp "$src" "$dest"
    "$STRIP" --strip-unneeded "$dest" 2>/dev/null || warn "could not strip $dest_name"
    printf '%s  %s  (%s bytes)\n' \
        "$(sha256sum "$dest" | cut -d' ' -f1)" "$dest_name" "$(stat -c%s "$dest")"
}

# Fails the build if a binary was linked for the wrong architecture — the
# mistake that produces an APK that installs and then crashes on first exec.
assert_arm64() {
    local f="$1"
    "$READELF" -h "$f" | grep -q 'AArch64' \
        || die "$f is not an AArch64 binary"
}
