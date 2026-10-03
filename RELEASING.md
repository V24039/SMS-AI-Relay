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

2. Create the **`release` environment**, which makes every release wait for your
   approval before the key is used. In the repository's **Settings → Environments →
   New environment**, name it `release` (exactly), then:

   - **Required reviewers**: tick it and add yourself (and any co-maintainer allowed
     to approve releases). Leave **Prevent self-review** off while you're the only
     reviewer, or you couldn't approve your own releases.
   - **Deployment branches and tags**: choose **Selected branches and tags** and add a
     **tag** rule `v*`, so only release tags can ever use the key.

   Create the environment *before* the first release: if the workflow runs first,
   GitHub creates `release` automatically with no reviewers, and the release goes
   through unapproved.

3. Add four **environment secrets** to `release` (on the same page, under
   **Environment secrets → Add environment secret**). Don't add them as repository
   secrets: those are readable by any workflow without approval.

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

4. Optional, for signed builds on your own machine: create `keystore.properties` in the
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

4. Approve it: in the **Actions** tab, open the Release run, click **Review deployments**,
   tick `release` and **Approve and deploy**. GitHub also emails the reviewers. Until
   someone approves, the run waits (for up to 30 days) and nothing is signed or published.

The workflow then checks that the tag matches `versionName`, runs the tests, builds and signs
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
