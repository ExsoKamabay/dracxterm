#!/usr/bin/env bash
#
# Check RootfsCatalog.kt against the checksums published upstream.
#
# The pins in RootfsCatalog.kt are the only thing standing between the user and an
# unverified archive being unpacked into the app sandbox, and they were transcribed by
# hand. This script re-reads them from the source file and compares them with the
# SHA256SUMS file that sits next to the images upstream, so a typo is caught here rather
# than by every user's download failing.
#
# Exit status: 0 = every pin matches, 1 = at least one mismatch or lookup failure.
#
set -uo pipefail

cd "$(dirname "$0")/.."
CATALOG="app/src/main/java/com/dracxterm/rootfs/RootfsCatalog.kt"
[ -f "$CATALOG" ] || { printf 'error: %s not found\n' "$CATALOG" >&2; exit 1; }

fetch() {
    if command -v curl >/dev/null 2>&1; then curl -fsSL "$1"
    elif command -v wget >/dev/null 2>&1; then wget -qO- "$1"
    else printf 'error: need curl or wget\n' >&2; return 1
    fi
}

# Pull "url" / "sha256" pairs out of the Kotlin source in file order.
mapfile -t URLS   < <(grep -oP 'url\s*=\s*"\K[^"]+'    "$CATALOG")
mapfile -t HASHES < <(grep -oP 'sha256\s*=\s*"\K[0-9a-fA-F]{64}' "$CATALOG")
mapfile -t NAMES  < <(grep -oP 'fileName\s*=\s*"\K[^"]+' "$CATALOG")

# The URLs are written as "$KALI_BASE/<file>"; resolve the constant.
BASE=$(grep -oP 'KALI_BASE\s*=\s*\n?\s*"\K[^"]+' "$CATALOG" || true)
if [ -z "${BASE:-}" ]; then
    BASE=$(grep -A1 'KALI_BASE' "$CATALOG" | grep -oP '"\K[^"]+' | head -1)
fi

if [ "${#HASHES[@]}" -eq 0 ]; then
    printf 'error: no sha256 pins found in %s\n' "$CATALOG" >&2
    exit 1
fi
if [ "${#HASHES[@]}" -ne "${#NAMES[@]}" ]; then
    printf 'error: %d hashes but %d file names in %s\n' \
        "${#HASHES[@]}" "${#NAMES[@]}" "$CATALOG" >&2
    exit 1
fi

printf 'catalog : %s\n' "$CATALOG"
printf 'base    : %s\n' "${BASE:-<unresolved>}"
printf 'entries : %d\n\n' "${#HASHES[@]}"

[ -n "${BASE:-}" ] || { printf 'error: could not resolve KALI_BASE\n' >&2; exit 1; }

sums=$(fetch "$BASE/SHA256SUMS") || { printf 'error: cannot fetch %s/SHA256SUMS\n' "$BASE" >&2; exit 1; }

status=0
for i in "${!NAMES[@]}"; do
    name="${NAMES[$i]}"
    pinned="${HASHES[$i]}"
    upstream=$(printf '%s\n' "$sums" | awk -v f="$name" '$2 == f { print $1 }')

    if [ -z "$upstream" ]; then
        printf '  \033[31mMISSING\033[0m  %s is not listed in the upstream SHA256SUMS\n' "$name"
        status=1
    elif [ "$(printf '%s' "$pinned" | tr 'A-Z' 'a-z')" = "$(printf '%s' "$upstream" | tr 'A-Z' 'a-z')" ]; then
        printf '  \033[32mOK\033[0m       %s\n' "$name"
    else
        printf '  \033[31mMISMATCH\033[0m %s\n' "$name"
        printf '           pinned   %s\n' "$pinned"
        printf '           upstream %s\n' "$upstream"
        status=1
    fi
done

echo
if [ "$status" -eq 0 ]; then
    printf 'All pins match upstream.\n'
else
    printf 'At least one pin does not match. Fix %s before releasing.\n' "$CATALOG"
    printf 'A wrong pin makes every download fail verification and be discarded.\n'
fi
exit "$status"
