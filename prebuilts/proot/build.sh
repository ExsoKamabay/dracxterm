#!/usr/bin/env bash
# Build libproot.so and libproot-loader.so — the user-space chroot that runs the
# Linux rootfs without device root, plus its ELF loader stub.
#
# Depends on libtalloc.so, so prebuilts/talloc/build.sh must have run first;
# build.sh in this directory's parent orders them correctly.
#
# Linked against libandroid-shmem to match the NEEDED set of the binary this
# replaces (libtalloc.so.2, libandroid-shmem.so, libc.so), so the runtime
# contract the app already relies on does not change.
#
# One thing that does change, deliberately: the binary this replaces was a
# Termux build and still contains hardcoded /data/data/com.termux/... paths for
# its library directory and loader. drac-Xterm works around that at runtime in
# Bootstrap (PROOT_LOADER and the library search path). A binary built here has
# no Termux paths in it at all.
. "$(dirname "$0")/../lib/common.sh"

RECIPE_DIR="$PREBUILTS_DIR/proot"

setup_toolchain
fetch_git "$PROOT_REPO" "$PROOT_COMMIT" proot

TALLOC_SRC="$WORK_DIR/src/talloc-$TALLOC_VERSION"
[ -f "$TALLOC_SRC/talloc.h" ] || die "talloc sources missing; run prebuilts/talloc/build.sh first"
[ -f "$OUT_DIR/libtalloc.so" ] || die "libtalloc.so not built; run prebuilts/talloc/build.sh first"
[ -f "$OUT_DIR/libandroid-shmem.so" ] || die "libandroid-shmem.so not built; run prebuilts/android-shmem/build.sh first"

# Build in a copy of src/. PRoot's GNUmakefile supports out-of-tree builds
# through VPATH, but only with relative paths — an absolute -f path makes it
# concatenate the source directory with itself and every .c "does not exist".
SRC="$WORK_DIR/build/proot"
rm -rf "$SRC"
mkdir -p "$(dirname "$SRC")"
cp -a "$DL_DIR/proot/src" "$SRC"

note "applying patches"
for p in "$RECIPE_DIR"/patches/*.patch; do
    [ -e "$p" ] || break
    printf '    %s\n' "$(basename "$p")"
    # Patches are written against the repository root (a/src/...), while $SRC is
    # that src/ directory, so strip two components rather than one.
    patch -s -p2 -d "$SRC" < "$p" || die "patch failed: $(basename "$p")"
done

# Dependencies, staged where the linker and compiler will find them.
mkdir -p "$SRC/dep-include/sys" "$SRC/dep-lib"
cp "$TALLOC_SRC/talloc.h" "$SRC/dep-include/"
cp "$OUT_DIR/libtalloc.so" "$SRC/dep-lib/"
cp "$OUT_DIR/libandroid-shmem.so" "$SRC/dep-lib/"
# bionic has no <sys/shm.h>; PRoot's sysvipc extension needs the declarations of
# shmget/shmctl and of libandroid_shmat_fd/libandroid_shmdt_fd, all of which come
# from libandroid-shmem's own header. android-shmem/build.sh staged it.
[ -f "$OUT_DIR/include/sys/shm.h" ] || die "sys/shm.h was not staged; re-run prebuilts/android-shmem/build.sh"
cp "$OUT_DIR/include/sys/shm.h" "$SRC/dep-include/sys/"

note "compiling"
make -C "$SRC" -j"$(nproc)" \
    CC="$CC" \
    LD="$CC" \
    OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy" \
    OBJDUMP="$TOOLCHAIN/bin/llvm-objdump" \
    STRIP="$STRIP" \
    PROOT_WITH_LIBANDROID_SHMEM=1 \
    CPPFLAGS="-I. -Idep-include -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE" \
    CFLAGS="-Wall -Wextra -O2 $REPRO_CFLAGS" \
    LDFLAGS="-Ldep-lib -ltalloc -landroid-shmem -Wl,-z,noexecstack -Wl,-z,max-page-size=16384" \
    > "$WORK_DIR/proot-build.log" 2>&1 \
    || {
        grep -E 'error:|Error [0-9]' "$WORK_DIR/proot-build.log" | sort -u | head -20 >&2
        die "proot build failed; full log at $WORK_DIR/proot-build.log"
    }

[ -f "$SRC/proot" ] || die "no proot binary was produced"
[ -f "$SRC/loader/loader" ] || die "no loader binary was produced"
assert_arm64 "$SRC/proot"
assert_arm64 "$SRC/loader/loader"

note "sanity checks"

# The NEEDED set is a runtime contract with Bootstrap, which sets up the library
# search path. A change here breaks launching with a linker error, not a message.
needed=$("$READELF" --dynamic "$SRC/proot" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | sort | tr '\n' ' ')
printf '    NEEDED: %s\n' "$needed"
for want in libtalloc.so.2 libandroid-shmem.so libc.so; do
    case " $needed " in
        *" $want "*) ;;
        *) die "proot does not link $want; the binary it replaces does" ;;
    esac
done

# The whole point of building from source rather than reusing the Termux binary.
if strings "$SRC/proot" | grep -q 'com\.termux'; then
    die "the built proot still contains com.termux paths; the build used the wrong source"
fi
printf '    no com.termux paths\n'

install_artifact "$SRC/proot" libproot.so
install_artifact "$SRC/loader/loader" libproot-loader.so
