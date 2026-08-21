# Licence texts for bundled third-party binaries

These are the licences covering the prebuilt binaries in
`app/src/main/jniLibs/arm64-v8a/`. See `docs/THIRD-PARTY-BINARIES.md` for which file is
covered by which licence, and for the written offer of corresponding source.

| File | Covers |
|---|---|
| `GPL-2.0.txt` | BusyBox, PRoot, PRoot loader |
| `LGPL-3.0.txt` + `GPL-3.0.txt` | talloc (LGPL-3.0 is written as a set of additional permissions on top of GPL-3.0, so both texts are required) |
| `BSD-3-Clause.txt` | android-shmem |

`GPL-2.0.txt`, `GPL-3.0.txt` and `LGPL-3.0.txt` are the canonical FSF texts as shipped in
Debian's `base-files` package (`/usr/share/common-licenses/`).

`BSD-3-Clause.txt` must be fetched from upstream so that the copyright line matches the
actual android-shmem authors rather than a generic template — run
`scripts/fetch-licenses.sh` once after cloning.
