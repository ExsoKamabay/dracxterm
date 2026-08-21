# ADR-0001: Deliver the Linux root filesystem on request instead of bundling it

**Status:** Accepted
**Date:** 2026-08-21
**Deciders:** Bang Jack (maintainer)
**Supersedes:** the delivery model introduced in CHANGELOG 1.0.1

## Context

Release 1.0.1 bundles `kali-nethunter-rootfs-nano-arm64.tar.xz` (206,922,656 bytes,
SHA-256 `484af462…36a23`) in `app/src/main/assets/rootfs/`, tracked with Git LFS. The
archive is marked `noCompress` in `app/build.gradle.kts`, so it lands in the APK
essentially at full size. The resulting APK is roughly 200 MB.

Forces at play:

1. **IzzyOnDroid reserves about 30 MB per app.** The repository runs on private
   resources with no funding. A 200 MB APK is around seven times that budget, and the
   repo keeps up to three versions per app.
2. **IzzyOnDroid forbids downloading executable binaries without explicit opt-in**
   consent that makes clear the user is stepping outside the repository's own checks.
   So "just download it" is not free of constraints either — the consent step is
   mandatory and must be honest.
3. **Git LFS bandwidth.** GitHub's free LFS tier is 1 GB of bandwidth per month. Five
   clones of this repository exhaust it, after which contributors get an LFS pointer
   file instead of the archive and the build silently produces a rootfs-less APK.
4. **Most of the payload is not ours.** The Kali image is a third-party distribution
   with its own licensing surface. Shipping it inside an Apache-2.0 APK drags a very
   large licence-compliance obligation into the release.
5. **The BusyBox fallback already exists and works.** `BootManager` already treats
   "no archive found" as a normal path to a working terminal, not an error.

## Decision

Stop shipping any root filesystem inside the APK. Provide three ways to obtain one,
in priority order:

1. an image the user already has on device, selected via the system file picker;
2. a download from a pinned upstream URL, started only after an explicit consent
   screen, verified against a pinned SHA-256 before extraction;
3. nothing — the app runs BusyBox, which stays the default, zero-network experience.

The existing asset-scanning path stays in the code, so anyone who builds the APK
themselves can still drop an archive into `assets/rootfs/` and get exactly today's
behaviour.

## Options considered

### Option A — Keep bundling, request a size exception

| Dimension | Assessment |
|---|---|
| Complexity | None (no code change) |
| Compliance | Fails the size guidance by ~7x |
| Bandwidth cost | Borne by IzzyOnDroid and by GitHub LFS |
| Reversibility | High |

**Pros:** zero engineering effort; offline first run.
**Cons:** the exception has to be granted by a volunteer paying for the hosting, at a
size far beyond the stated reserve; LFS bandwidth still breaks contributor clones;
the licence surface stays maximal.

### Option B — Two product flavours (`lite` for the repo, `full` on GitHub)

| Dimension | Assessment |
|---|---|
| Complexity | Medium — two build paths, two artefacts per release |
| Compliance | Passes on size; needs distinct `applicationId` to avoid an update clash |
| Maintenance | Doubles the release and test matrix |
| Reversibility | Medium |

**Pros:** offline install stays available for people who want it.
**Cons:** IzzyOnDroid requires unique package *and* display names, so the two builds
become two apps that cannot update each other; users who install the wrong one have to
uninstall and lose their sandbox; every release has to be signed, tested and published
twice.

### Option C — On-request delivery (chosen)

| Dimension | Assessment |
|---|---|
| Complexity | Medium — one new downloader, one consent screen, a source abstraction |
| Compliance | Passes on size; satisfies the opt-in rule when the consent text is honest |
| APK size | ~200 MB → single-digit MB |
| Reversibility | High — the asset path is retained, so re-bundling is a build-time choice |

**Pros:** the APK carries only drac-Xterm's own code plus its prebuilt runtime; the
user chooses the distribution and sees where the bytes come from; contributors can
clone without Git LFS.
**Cons:** the first Linux launch needs a network connection and roughly 200 MB of
transfer; a new failure surface (interrupted, corrupted or redirected downloads) has to
be handled properly rather than hand-waved.

## Trade-off analysis

The decisive trade is **offline first-run convenience** against **being distributable at
all**. Option A optimises the former and loses the latter. Option B keeps both but pays
for it with a permanently forked release process and a user-visible package split —
expensive for a single-maintainer project.

Option C's real cost is not the download; it is that the download must be done
*correctly*. An unverified or partially-written archive that reaches the extractor is
worse than no archive at all, because `RootfsExtractor` will happily unpack a truncated
tar and leave a broken sandbox that `RuntimeValidator` then has to catch. The mitigation
is mechanical and testable: write to `.part`, hash the completed file, and only then
promote it to the cache path the extractor reads.

The consent screen is not a checkbox exercise either. IzzyOnDroid's requirement is that
the user understands they are fetching something the repository has not inspected. The
copy has to say that plainly, name the exact host and size, and default to "no".

## Consequences

**Easier**
- APK fits the repository budget with room to spare.
- Contributors clone without Git LFS; CI builds stop depending on LFS quota.
- The licence obligation narrows to the five prebuilt binaries we actually ship
  (see `docs/THIRD-PARTY-BINARIES.md`).
- The user picks their distribution instead of inheriting ours.

**Harder**
- First Linux launch requires ~200 MB of network transfer and roughly 1 GB of free
  space for archive plus extraction.
- The pinned SHA-256 has to be updated whenever the upstream image is refreshed;
  Kali's `current` path is a moving target, so the pin must reference an immutable
  release path or the app must accept a user-supplied hash.
- One more screen, one more set of strings to translate, one more failure mode to test.

**To revisit**
- Whether to mirror a known-good image under GitHub Releases so the pin can point at an
  immutable artefact instead of Kali's rolling directory.
- Whether the "bring your own archive" path should also accept a user-entered checksum.

## Action items

1. [ ] Introduce `RootfsSource` (asset | local file) and route discovery, validation and
       extraction through it.
2. [ ] Add `RootfsDownloader`: pinned URL, `.part` staging, SHA-256 gate, resume,
       cancellation.
3. [ ] Add the consent screen; default action is "Continue with BusyBox".
4. [ ] Delete `app/src/main/assets/rootfs/*.tar.xz` and the Git LFS attribute.
5. [ ] Keep `assets/rootfs/README.txt` describing the still-supported self-build path.
6. [ ] Verify: APK size, BusyBox path, asset path, download path, corrupted-download
       path, cancelled-download path.
