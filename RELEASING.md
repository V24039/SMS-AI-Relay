# Releasing

Releases are built and signed by GitHub Actions (`.github/workflows/release.yml`) when a
version tag is pushed. The signing key never goes in the repository.

## One-time setup: the signing key

Every release must be signed with the **same key**, forever. Android refuses to install
an update signed with a different key, so users would have to uninstall (and lose their
settings and history) to upgrade. Back the key up somewhere safe, e.g. a password manager.

1. Create the key (needs a JDK; `keytool` is in its `bin` folder). Choose your own passwords
   when it asks:

   ```bash
   keytool -genkeypair -v -keystore release.jks -alias sms-ai-relay -keyalg RSA -keysize 4096 -validity 10000
   ```

   `release.jks` is gitignored (`*.jks`). Keep it outside the repo folder if you prefer.

2. Add four repository secrets on GitHub (Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | the key file, base64-encoded (see below) |
   | `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
   | `RELEASE_KEY_ALIAS` | `sms-ai-relay` |
   | `RELEASE_KEY_PASSWORD` | the key password |

   To base64-encode the key file in PowerShell and copy it to the clipboard:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
   ```

   On Linux/macOS: `base64 -w0 release.jks` (macOS: `base64 -i release.jks`).

3. Optional, for signed builds on your own machine: create `keystore.properties` in the
   project root (gitignored):

   ```properties
   storeFile=release.jks
   storePassword=...
   keyAlias=sms-ai-relay
   keyPassword=...
   ```

## Cutting a release

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` with a few lines
   on what changed (F-Droid shows it; max 500 characters).
3. Commit, then tag with `v` + the exact `versionName` and push the tag:

   ```bash
   git tag v0.7.0
   git push origin v0.7.0
   ```

The workflow checks that the tag matches `versionName`, runs the tests, builds and signs
the APK, verifies the signature, and publishes a GitHub Release with the APK and its
SHA-256 checksum.

## Blocking merges without tests

CI (`.github/workflows/ci.yml`) fails a pull request when less than 80% of the app
lines it adds or changes are run by a unit test (JaCoCo line coverage checked with
`diff-cover`). The check's summary page lists the untested lines. A failing check only
*blocks* merging once GitHub is told to require it. After the first push, in the
repository's Settings:

1. **Branches → Add branch protection rule** (or **Rules → Rulesets**) for `master`.
2. Turn on **Require a pull request before merging**, so nobody pushes straight past CI.
3. Turn on **Require status checks to pass**, and add the **`test`** check. It only
   appears in the list after CI has run once.
4. Optionally turn on **Do not allow bypassing the above settings** so the rule applies
   to you as well.

Create a label named **`coverage-exempt`** (Issues → Labels). Adding it to a pull
request skips the coverage check. Use it only for code that really can't be
unit-tested (the real Android Keystore, actual SMS sending, the receiver's fallback
when a service start is refused), and say why in the PR. Only people with write access
can add labels, so contributors can't exempt themselves.

To see coverage locally, run `./gradlew createDebugUnitTestCoverageReport` and open
`app/build/reports/coverage/test/debug/index.html`.

## F-Droid

F-Droid builds the app from source and signs it with its own key, so the F-Droid build
and the GitHub Release APK can't be installed over each other. Users need to pick one.

To get listed, open a merge request on [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
adding `metadata/com.smsairelay.app.yml`. A draft is in `fdroid/com.smsairelay.app.yml`.
Fill in the repository URL and check it against the current F-Droid docs first. The store
text (title, descriptions, changelogs) is read from `fastlane/metadata/` in this repo.
