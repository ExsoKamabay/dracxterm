# Signing key rotation and history purge

**Severity: high. Do this before publishing any release.**

## What happened

`keystore/dracos-release.keystore` (2,744 bytes) is committed to the public repository at
`github.com/ExsoKamabay/dracxterm`. Anyone can download it. The local `.gitignore`
contains `/keystore/`, but that rule was added *after* the file was already tracked, and
`.gitignore` never affects files git already knows about.

A keystore alone is not enough to sign — the store and key passwords are still needed —
but the file is the long-lived half of the secret and it is now public and permanently
archived by third-party mirrors of GitHub. Treat the key as burned.

**Why it matters more than it looks:** on Android, the signing key *is* the app's
identity. Once a release is published under a key, every future update must be signed
with that same key or devices refuse the update. If a leaked key is ever used to sign a
public release, there is no clean recovery: an attacker who cracks the passwords can
produce APKs that install as legitimate updates over yours.

No release has been published yet — 0 tags, 0 releases on the repository. That is the
only reason this is cheap to fix right now. It stops being cheap the moment v1 ships.

## Fix, in order

Steps 1, 2 and 5 are automated by `scripts/release-prep.sh rotate-key`, which generates
the key, writes the four properties with the right file mode, and proves the result signs
a release before it tells you it worked. It refuses to overwrite an existing keystore.
The manual instructions below remain the specification of what it does — read them if you
would rather do it by hand, or to check the script.

Step 3 is already done. Step 4 cannot be automated: only GitHub Support can purge the
pull-request refs.

### 1. Generate a new key, outside the repository

```sh
mkdir -p ~/.android-keys && chmod 700 ~/.android-keys
keytool -genkeypair -v \
  -keystore ~/.android-keys/dracxterm-release.jks \
  -alias dracxterm \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype PKCS12
```

Use a long, unique passphrase that is not reused anywhere else. Back the file up
somewhere offline; losing it means never being able to update the app again.

### 2. Put the credentials in your user Gradle properties, never in the repo

`~/.gradle/gradle.properties`:

```properties
DRACOS_STORE_FILE=/home/dracos/.android-keys/dracxterm-release.jks
DRACOS_STORE_PASSWORD=…
DRACOS_KEY_ALIAS=dracxterm
DRACOS_KEY_PASSWORD=…
```

```sh
chmod 600 ~/.gradle/gradle.properties
```

`app/build.gradle.kts` now reads `DRACOS_STORE_FILE` and fails the release build if it is
missing, so no keystore path inside the repository is referenced any more.

### 3. Remove the file from the working tree and from history

**Done on 2026-08-22, by replacement rather than by rewrite.** The working tree was moved
to `~/Desktop/dracxterm-keystore-quarantine/dracos-release.keystore.COMPROMISED`, and the
repository history was started fresh: the eight old commits were replaced by a single
initial commit that never contained `keystore/` at all. The previous remote history is
preserved locally at `~/Desktop/dracxterm-remote-backup.bundle` and
`~/Desktop/dracxterm-remote-backup/`.

That path was chosen over a filter-repo rewrite because the repository had eight commits
by one author and zero published releases, so there was nothing to preserve that was worth
the extra failure modes of a history rewrite.

**It did not make the blob unreachable, and nothing a repository owner can do would
have.** Verified immediately after the force-push: the old commits still resolve by SHA,
and

    GET /repos/ExsoKamabay/dracxterm/contents/keystore/dracos-release.keystore?ref=d5bb6ac

still returns the 2,744-byte keystore. GitHub keeps merged pull requests' commits alive
through `refs/pull/1/head` and `refs/pull/2/head`, which a force-push does not touch and
which the repository owner cannot delete. A filter-repo rewrite would have left exactly
the same refs behind. This is a property of GitHub, not a shortcoming of the method
chosen.

Remaining reachability, and who can close it:

| Path | Status | Who can remove it |
|---|---|---|
| `refs/heads/main` | closed — replaced by a history the blob was never in | done |
| `refs/heads/add/add-kali-nethunter-rootfs` | **open** — merged-PR leftover still pointing at `94f24cf` | the maintainer: `gh api -X DELETE repos/ExsoKamabay/dracxterm/git/refs/heads/add/add-kali-nethunter-rootfs` |
| `refs/pull/1/head`, `refs/pull/2/head` | **open** | GitHub Support only (step 4 below) |

The practical conclusion does not change and is the reason step 1 is not optional:
**assume the keystore file is public forever.** Rotation is the fix; history hygiene only
narrows how easily it is found.

If the situation ever recurs on a repository whose history *is* worth keeping, the rewrite
path still exists:

```sh
./scripts/purge-keystore-history.sh
```

The script is deliberately not automatic about the destructive parts — read it before
running. In outline it: verifies you are in a clean clone, runs `git filter-repo` (or
BFG) to strip `keystore/` from every commit, expires reflogs, repacks, and then prints
the exact force-push command for you to run yourself.

After force-pushing:

```sh
git log --all --oneline -- keystore/          # must print nothing
git rev-list --objects --all | grep -i keystore   # must print nothing
```

Then, in a scratch directory:

```sh
git clone --no-local https://github.com/ExsoKamabay/dracxterm fresh-check
cd fresh-check && git log --all -- keystore/   # must print nothing
```

### 4. Ask GitHub to drop the cached objects

Rewriting history does not immediately remove blobs that are still reachable through
old pull-request refs or the object cache. Open a support request at
<https://support.github.com/> naming the repository and the removed path, and ask for
stale cached views to be purged. Also delete any fork you control.

### 5. Sanity-check the new signature before releasing

```sh
./gradlew clean assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Record the certificate SHA-256 in your own notes. IzzyOnDroid pins the signature of the
first APK it ingests; every later release must match it.

## Things not to do

- **Do not** just `git rm` the file and commit. The blob stays in history and is trivially
  recoverable.
- **Do not** reuse the old key "because nobody has the password". The passwords are the
  only thing standing between a public file and a valid signature over your package name.
- **Do not** commit `keystore.properties`, `local.properties`, or `~/.gradle` contents as
  a convenience for CI. For CI, put the keystore in an encrypted secret and decode it at
  build time (see `.github/workflows/release.yml`).
