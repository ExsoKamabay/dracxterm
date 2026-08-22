# IzzyOnDroid submission checklist

Status of drac-Xterm against the published requirements. Sources: the
[App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/), the
[Fastlane structure docs](https://izzyondroid.org/docs/general/Fastlane/), the
[developer hints](https://izzyondroid.org/docs/devpractices/Misc/) and the
[repo info page](https://android.izzysoft.de/repo/info).

Legend: **done** — verified in this repository · **pending** — needs an action listed
below · **risk** — a judgement call that submission may turn on.

Everything marked *done* on a mechanical rule is re-checked on every push and on every
release tag by `scripts/verify-izzy-metadata.py`. If that script exits non-zero, this
table is wrong and the script is right.

## Hard requirements

| # | Requirement | Status | Evidence / action |
|---|---|---|---|
| 1 | Free and open source under an OSI/FSF-approved licence | done | `LICENSE` (Apache-2.0) at the repository root, plus `NOTICE` and `licenses/` (GPL-2.0, GPL-3.0, LGPL-3.0, BSD-3-Clause) for the bundled binaries |
| 2 | Source publicly accessible | done | `github.com/ExsoKamabay/dracxterm`, public |
| 3 | No trackers, no ads, no analytics | done | Dependencies are androidx, Material, commons-compress, tukaani-xz, zstd-jni — none of them phone home. Worth re-checking with [exodus](https://reports.exodus-privacy.eu.org/) on the release APK |
| 4 | No proprietary components | done | Every bundled binary is GPL/LGPL/BSD; see `docs/THIRD-PARTY-BINARIES.md` |
| 5 | Unique package name | done | `com.dracxterm` |
| 6 | Unique display name | done | Renamed `xterm` → `drac-Xterm`. `xterm` collided with the X11 terminal emulator |
| 7 | End-user app, not a library or demo | done | — |
| 8 | Fastlane metadata in the repo | done | `fastlane/metadata/android/{en-US,id}/` |
| 9 | `short_description.txt` ≤ 80 chars | done | 71 (en-US), 69 (id) |
| 10 | `full_description.txt` ≤ 4000 chars | done | 2140 (en-US), 2238 (id) |
| 11 | `changelogs/<versionCode>.txt` ≤ 500 bytes | done | `3.txt` — 431 bytes (en-US), 420 (id) |
| 12 | Icon 48–512 px, PNG/JPG | done | `images/icon.png`, 512×512 |
| 13 | Screenshots, longer:shorter edge ≤ 2:1 | done | Six screenshots at 822×1600 (1.95:1) |
| 14 | Signed release build, not debuggable | done | **Measured**: `assembleRelease` refuses to run without credentials (verified — it fails with "Refusing to build an unsigned release"), and a signed build carries no `application-debuggable` flag. Signature schemes are set explicitly: v1 off, v2 + v3 on |
| 15 | APK within the ~30 MB reserve | done | **Measured**: release APK is **9,319,452 bytes (9.32 MB)**, debug 10.8 MB. `assets/rootfs/` inside the APK holds only `README.txt` (1,986 bytes) |
| 16 | No binary downloads without explicit opt-in | done | `ProvisioningActivity` consent panel; nothing is fetched at startup; the copy names the host, the size, and says the file is outside the store's checks |
| 17 | Releases published as tagged GitHub releases | **pending** | 0 tags, 0 releases. `.github/workflows/release.yml` publishes on `v*` tags and its gates are in place, but it has never run — it needs the four `DRACOS_*` repository secrets, which need a rotated key. **Blocked on row 20** |
| 18 | Tag name matches versionName | done | A workflow step fails the build on mismatch, and also fails when any locale is missing a changelog for the `versionCode` being released |
| 19 | Never replace a published APK | — | Process rule. `gh release create --verify-tag` only ever creates a release for the tag that triggered the run |

## Repository hygiene

| # | Item | Status | Action |
|---|---|---|---|
| 20 | Signing keystore not in the repo | **pending** | Out of the working tree (quarantined as `~/Desktop/dracxterm-keystore-quarantine/dracos-release.keystore.COMPROMISED`), `.gitignore` covers it, and `main` was force-pushed to a history that never contained it. **It is still downloadable** through `refs/pull/{1,2}/head`, which no repository owner can delete — verified after the push. Two actions remain, both the maintainer's: rotate the key (`docs/SECURITY-KEY-ROTATION.md` step 1), and delete the stale `add/add-kali-nethunter-rootfs` branch, which still points at the old history |
| 21 | Local tree in sync with the remote | done | The working tree is now a git repository and `main` matches it. `fastlane/`, `licenses/`, `scripts/`, `.github/`, `prebuilts/`, `CHANGELOG.md` and `docs/adr/` are on the remote; the LFS pointer and the LFS `.gitattributes` rule are gone |
| 22 | Git LFS not needed to build | done | No LFS rule on either side; a plain `git clone` produces a buildable tree |
| 23 | Distribution links point at releases, not a personal drive | done | README links GitHub Releases; the Google Drive links are gone |
| 24 | The project builds from a clean checkout | done | **Verified**: `./gradlew assembleDebug` and `assembleRelease` both succeed, `./native-tests/run-tests.sh` passes 160 assertions, 0 failures. This required a fix — `RootfsArchive.sizeBytes` did not compile (smart cast lost inside a `runCatching` lambda), so *no* build of this tree was possible before |
| 25 | Build is reproducible across machines | partial | `ndkVersion` is pinned to 27.0.12077973 so the C++ engine does not depend on whichever NDK a machine has. `prebuilts/` rebuilds all five prebuilt binaries from pinned sources **bit-identically across machines** — a GitHub runner reproduces every hash this desktop produces, re-checked by CI weekly. The APK itself is not RB-verified: that needs the release key and IzzyOnDroid's own toolchain |

## Risks worth deciding on deliberately

**Generative-AI policy.** The inclusion policy states that IzzyOnDroid is "strongly
opposed to apps which are fully or in part created by generative AI tools". This
repository carries signs of AI-assisted development. That is a factual matter for the
maintainer to represent accurately if asked; it is not something this checklist can
resolve, and it is the single largest threat to acceptance regardless of how well every
other row above is satisfied. Consider raising it directly when requesting inclusion
rather than leaving it to be discovered.

**`MANAGE_EXTERNAL_STORAGE`.** Defensible for a terminal emulator, and the app stays
fully functional when it is denied, but it is a permission that draws scrutiny. The
`full_description.txt` explains that storage access is optional and never requested at
startup — keep it that way.

**BusyBox 1.29.3 (2018).** Old enough that CVEs have accumulated upstream. Not an
inclusion blocker, but it is a real security item and reviewers do look at bundled
binaries. **The fix now exists and is unblocked**: `./prebuilts/build.sh` produces
BusyBox 1.38.0. It has not been installed, because a seven-year jump needs device
testing, not a green build. Doing that is the single highest-value item left before
submission.

**~~No reproducible-build recipe for the prebuilts.~~ Resolved.** `prebuilts/build.sh`
builds all five from pinned upstream sources with the pinned NDK, and produces the same
bytes from any build directory. That removes the structural blocker on RB status.

What remains is a product decision rather than a missing capability: the binaries in the
APK are still the inherited Termux/2018 ones. Swapping in the freshly built set — which
also moves BusyBox from 1.29.3 to 1.38.0 — changes the runtime and must be exercised on
real hardware first. See `prebuilts/README.md`.

## Known gap, tracked deliberately

**No in-app way back after declining the Linux image.** `ProvisioningState.imageOfferDeclined`
makes the offer a one-time question, which is the right default — but there is currently no
`xset` entry to re-open it, so a user who declines and later changes their mind has to clear
the app's data. An `xset` module would need a new `XsetContext` method and a change to
`MainActivity`, which implements that interface; that was left out of this change rather than
made blind and unverified. The consent copy was written to match what the app actually does
("drac-Xterm will not ask again on later launches") rather than to promise a screen that does
not exist yet.

## Verification status

Proven on 2026-08-22, on this tree:

1. **done** — `./gradlew assembleRelease` completes and the APK is 9,319,452 bytes
   (9.32 MB), signed v2 + v3, not debuggable, `native-code: arm64-v8a`, `versionCode=3`,
   `versionName=1.1.0`, label `drac-Xterm`. Measured with a throwaway key that was
   destroyed immediately afterwards, together with the APK it signed.
2. **done** — `./gradlew assembleDebug` completes; APK 10,819,192 bytes.
3. **done** — `./native-tests/run-tests.sh`: 160 assertions, 0 failures.
4. **done** — `scripts/verify-izzy-metadata.py`: 0 failures, 0 warnings.
5. **done** — the release build refuses to produce an unsigned APK when credentials are
   absent, and says why.

Still owed, and not claimable from a desktop:

6. The app is installed on an arm64 device and each provisioning path is exercised:
   BusyBox-only, consent declined, download completed, download cancelled and resumed,
   deliberately corrupted `.part` rejected, and an image bundled in `assets/rootfs/`.
7. `scripts/verify-rootfs-catalog.sh` exits 0 against upstream. Note: the archive kept
   locally at `~/Desktop/dracxterm-local-assets/` hashes to `484af462…36a23`, which is
   the *old* image referenced in ADR-0001 — **not** the `2ea1c504…1e4e` pinned in
   `RootfsCatalog`. Do not treat the local copy as a check of the pin.
8. The release APK is scanned with exodus / VirusTotal before submission.
9. The freshly built prebuilts (`./prebuilts/build.sh`) are exercised on an arm64 device:
   the BusyBox fallback shell first, then a full rootfs provisioning and PRoot launch.
   They compile, link, are AArch64, and carry the expected symbols and `NEEDED` entries —
   none of which is evidence that they run.

## Submitting

Contact details for requesting inclusion are on the
[IzzyOnDroid imprint](https://android.izzysoft.de/imprint). Have ready: the repository
URL, the tagged release URL, the licence, and a one-line description of what the app
does and who it is for.

Order of operations, because rows 17, 20 and 21 are entangled. `scripts/release-prep.sh`
drives all of it and enforces the order — each step refuses to run until the ones it
depends on have actually happened, checked against the world rather than a marker file:

```sh
./scripts/release-prep.sh              # what is done, what is not
./scripts/release-prep.sh rotate-key   # 1. new signing key, outside the repo
./scripts/release-prep.sh clean-refs   # 2. delete the branch still holding the old history
./scripts/release-prep.sh device-test  # 3. run the rebuilt prebuilts on real arm64 hardware
./scripts/release-prep.sh secrets      # 4. upload the four DRACOS_* secrets
./scripts/release-prep.sh tag          # 5. tag v1.1.0; CI builds and publishes
```

Step 3 is only a gate on `./prebuilts/build.sh --install`, not on this release — the APK
still ships the inherited binaries. The remaining manual item is asking GitHub Support to
purge the pull-request refs (step 4 of `docs/SECURITY-KEY-ROTATION.md`), which no script
can do.

Only then request inclusion.
