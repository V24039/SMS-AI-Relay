# SMS AI Relay

Standalone Android app: text it a question over SMS, it answers using an AI API — no server, no backend, no bundled API key. Built for the "signal but no data" situation (rural travel, no plan/roaming, carrier outage) where SMS still works but a mobile data connection doesn't.

## Core idea

One phone (a spare/dedicated device with Wi-Fi or data, left at home/office) runs this app. You text that phone's number from wherever you are — even with zero data on your own phone, SMS still goes through. The app receives the SMS, calls an AI API directly using a key the user supplied themselves, and texts the reply back. Everything — API key, conversation history, settings — lives only on that one device.

This is deliberately **not** a client/server system. Earlier design passes considered a cloud backend (webhook server + Twilio) and a two-app split (phone gateway + separate server), but the project settled on a single self-contained app because: no infra to run or pay for, no shared API key to protect, trivially installable by anyone (sideload one APK), and it matches "bring your own AI/your own key" — the whole point of open-sourcing it.

## Non-goals

- No cloud/server component of any kind.
- No multi-tenant user accounts or usage limiting — it's single-owner, one API key, one device.
- No Play Store distribution (see **Distribution** below) — sideload / F-Droid only.
- Not a general SMS-forwarding or automation platform — scope is narrowly "SMS in, AI reply out."

## Architecture

```
Sender's phone (SMS only, no data needed)
   → carrier SMS →
Relay device (runs this app, has internet)
   → manifest BroadcastReceiver catches SMS_RECEIVED_ACTION
   → sender checked against allowlist (Settings)
   → handed to the RelayService foreground service (SmsRelay pipeline)
   → Room DB: load conversation history for that sender number
   → reset policy evaluated (inactivity timeout / keyword / token budget)
   → selected AI provider (Claude / Gemini / Gemma / OpenAI / Groq / Cerebras / Mistral / OpenRouter) called with system prompt + history + new message
   → response appended to history in Room
   → SmsManager sends the reply (multipart if long)
```

No network component other than the outbound HTTPS call to the AI provider. No component the app doesn't own runs on any other machine.

## Tech stack

Items marked *(planned)* describe the target design and are **not built yet**. Don't assume they exist.

- **Language**: Kotlin, min SDK 26, target/compile SDK 34.
- **Background trigger**: manifest-registered `BroadcastReceiver` for `android.provider.Telephony.SMS_RECEIVED` (exempted from Android 8+ implicit-broadcast background restrictions, so it fires even when the app isn't running). `onReceive()` checks the allowlist first, then hands the message to `RelayService` — no blocking work on the receiver's thread.
- **Processing**: the pipeline (DB read, API call, DB write, SMS send) lives in `SmsRelay` and runs one message at a time behind a process-wide `Mutex`. Normally it runs inside `RelayService`, a long-running foreground service (type `specialUse`, `START_STICKY`) with a low-importance ongoing notification. A foreground-service process keeps network access during Doze and isn't killed mid-call. If Android refuses to start the service from the background (Android 12+ without the battery exemption, when the service isn't already running), the receiver falls back to running `SmsRelay` itself inside its ~10s `goAsync()` window.
- **Persistence**: Room (SQLite), on-device only. No external DB, no sync.
  - One `messages` table keyed by the normalised sender number (`SenderAllowlist.conversationKey`).
  - Last activity is the newest message timestamp and the token estimate is computed at load time, so neither is stored separately.
- **AI client**: plain OkHttp + `org.json` (bundled with Android), direct HTTPS calls to the provider the user picks in Settings — Claude (Messages API), Gemini or the open-weight Gemma models (both via Google AI Studio's `generateContent`, sharing one key through `AiProvider.keySlot`), OpenAI (Chat Completions), or the OpenAI-compatible Groq, Cerebras, Mistral and OpenRouter (same `OpenAiClient`, different base URL, each with its own key slot). Each is one `AiClient` implementation (`AiClient.kt`); API key and optional model override are stored per provider. OpenAI uses Chat Completions (not the Responses API) because Groq, OpenRouter, Mistral, Cerebras etc. accept the same shape, so they're just `OpenAiClient` instances with a different base URL. Per-provider differences are constructor parameters: `maxTokensField` (Mistral documents only `max_tokens`; OpenAI's reasoning models reject it, so the default is `max_completion_tokens`), `defaultReasoningEffort` (only sent with the default model) and `providerName` for error messages. `parseReply` strips `<think>…</think>` blocks, which some open reasoning models (Qwen on Groq, OpenRouter's free router) put in the reply text; an unclosed `<think>` counts as no answer. Retrofit was considered but dropped — a handful of single endpoints isn't worth the converter/interface machinery.
- **Settings/secrets**: entered by the user on the Settings screen and stored by `SettingsStore` — provider, per-provider API key and model override, allowlist, idle timeout. Keys are never hardcoded or bundled and only leave the device in the auth header of requests to the selected provider. API keys are AES-256-GCM encrypted (`SecretCipher`) with a non-exportable Android Keystore key before going into `SharedPreferences`; plaintext keys from older versions are encrypted and removed on first read. The other settings aren't secret and stay plain. `EncryptedSharedPreferences` was the original plan but `androidx.security:security-crypto` is deprecated, so this uses the Keystore directly instead. If the Keystore key is ever lost, decryption returns null and the user re-enters the key.
- **Backup**: `allowBackup="false"` plus `data_extraction_rules.xml` excluding every domain, so keys, allowlist and history never leave the device via cloud backup or device-to-device transfer.
- **Sending SMS**: `SmsManager.sendMultipartTextMessage()` for replies that exceed one segment.
- **Reliability**: the main screen shows whether battery optimization is off and asks for the exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, direct prompt with a fallback to the system list). The exemption gives network access in Doze and is an allowed reason to start a foreground service from the background on Android 12+. `BootReceiver` restarts the service on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, both allowed foreground-service start triggers; `MainActivity` also starts it whenever it's shown with SMS permissions granted. OEM battery managers (Xiaomi, Samsung, etc.) can still kill the app beyond stock Android's rules; that needs per-device user steps, not code. The notification permission (Android 13+) isn't requested; the service runs without it and the notification just isn't shown.

## Session / context reset policy

The original pain point this project exists to solve (context window growth + drift over a long-lived SMS thread), handled entirely on-device, no scheduler process needed — evaluated at message-receive time. Logic and constants live in `ConversationHistory.kt`:

1. **Explicit keyword** — a message that is exactly `/new` or `RESET` (trimmed, any case) deletes that sender's history and replies "New conversation started." without calling the AI, so it works even with no API key set. Longer messages containing the word go to the AI as normal.
   **Stop command** — `/stop` or `STOP` (same exact-match rule) cancels that sender's queued, in-progress and partly-sent replies. Long replies are split (`ConversationHistory.splitForSms`, ~450 chars) and sent one piece per `SmsRelay.chunkDelayMs` (4s), so there is time to stop. The stop path skips the pipeline `Mutex` (the send loop holds it) and cancels the registered coroutine `Job`s; it replies "Stopped." / "Nothing to stop." without calling the AI. In the receiver's `goAsync()` fallback the ~10s window can cut a long reply short.
2. **Inactivity timeout** — default 45 min, editable in Settings (invalid input falls back to the default). On every incoming message, *all* conversations idle past the timeout are deleted, not just the sender's.
3. **Token budget** — each request carries only the newest turns fitting ~4000 estimated tokens (`chars / 4`). The new message is always sent even if it alone exceeds the budget. Leading assistant turns left by trimming are dropped, since providers need the conversation to start with a user turn.
4. **Storage cap** — at most 100 rows per sender; older rows are trimmed after each save.
5. A user/assistant pair is saved only after the model answers, so a failed call never leaves an unanswered user turn in history.
6. The system prompt (`SMS_SYSTEM_PROMPT` in `AiClient.kt`) is never stored in history, so a reset never loses it.

## Security model

Even though there's no shared key or multi-tenant abuse surface to worry about, the app is still reachable by SMS from *anyone who has the number* — so:

- **Sender allowlist** (Settings, built) — only phone numbers the owner explicitly adds get a response; everything else is dropped silently before any API call or SMS is spent. An empty allowlist replies to nobody. Numbers match on their last 10 digits, so `+91 98765 43210` and `098765 43210` are the same sender; shortcodes and alphanumeric sender IDs never match. This protects the owner's own API spend and inbox, not other users.
- API keys encrypted at rest with a Keystore-held key (built). Conversation history and the allowlist are not encrypted; they rely on app-private storage and the backup exclusions.
- No telemetry, no analytics, no third-party network calls other than the configured AI provider's API.

## Distribution

`RECEIVE_SMS` / `SEND_SMS` are restricted permissions under Google Play policy (generally Play-approved only for declared default-SMS-handler apps). This project is **not** going to become a full default SMS app, so:

- Ship via **GitHub Releases** (signed APK) and optionally **F-Droid**.
- **License**: GPL-3.0-only (`LICENSE`).
- **Disclosure**: the README states that the project was entirely AI-generated (code and tests) and that only on-device testing was done by a human. Keep that notice accurate if this changes.
- **Release signing**: `app/build.gradle.kts` reads the key from gitignored `keystore.properties` or `RELEASE_*` env vars (CI); with neither, release builds are unsigned, which is what F-Droid wants since it signs with its own key. `dependenciesInfo` is disabled because F-Droid rejects Google's encrypted dependency-metadata block.
- **CI**: `.github/workflows/ci.yml` runs tests and a debug build on pushes and PRs; `release.yml` runs on `v*` tags, checks the tag matches `versionName`, and publishes the signed APK plus SHA-256 to GitHub Releases.
- **F-Droid**: store text lives in `fastlane/metadata/android/en-US/` (a changelog per versionCode). `fdroid/com.smsairelay.app.yml` is a draft for the fdroiddata merge request, not used by this repo. It declares the `NonFreeNet` anti-feature because the app talks to proprietary AI APIs.
- README must say explicitly: sideload / "install from unknown sources," this will not be on the Play Store, and explain why.

## Prior art (why this project, not an existing one)

Checked before starting — see the roadmap artifact conversation for full notes:

- **SMSgpt** — closest concept (phone as SMS↔ChatGPT gateway) but requires a tethered PC over ADB, one-message-at-a-time, no long-SMS support, and the repo is archived/dead.
- **TARS** — server-based, uses a carrier email-to-SMS gateway hack (IMAP polling), OpenAI-only, low activity, unpredictable delivery timing.
- **android-sms-relay** (nyaruka) — generic, maintained SMS↔HTTP relay, but not AI-specific and still requires a separate server.

Gap this project fills: a single, standalone, actively maintained APK — no PC, no server, no email-gateway trick, bring-your-own API key, local-only storage.

## Roadmap / build phases

Full interactive version: the "Relay Roadmap" artifact from project planning. Summary, in order, with status:

1. **Bare Signal** — permissions, receiver, hardcoded echo reply. *Done, verified on device.*
2. **First Real Reply** — wire in the Claude API, single-turn, no memory yet. *Done, verified on device.*
3. **Memory** — Room-backed per-sender conversation history, multi-turn context. *Done, verified on device.*
4. **Knowing When to Forget** — inactivity timeout, keyword reset, token-budget trim. *Built and committed; not yet verified on device.*
5. **Locking the Door** — sender allowlist *(done)*, encrypted API key storage *(built; not yet verified on device)*.
6. **Staying Alive** — battery exemption, boot receiver, foreground service hardening. *Built; not yet verified on device.*
7. **Out the Door** — README (with the AI-generated disclosure), GPL-3.0 license, CI tests on every push, signed release APK built by CI on `v*` tags, F-Droid store metadata and a draft fdroiddata recipe. *Built; the signing key and GitHub secrets still have to be set up by the maintainer (see `RELEASING.md`), and the repo URL is a placeholder `OWNER/sms-ai-relay` until the GitHub repo exists.*
8. **Bring Your Own AI** — provider interface with Claude, Gemini, Gemma (Google AI Studio) and OpenAI, plus the OpenAI-compatible providers with free tiers: Groq, Cerebras, Mistral and OpenRouter. Cloud APIs only, no on-device models. *Built; the new providers not yet tried with real keys.* A possible next step is a user-configured custom OpenAI-compatible endpoint (base URL in Settings), e.g. for Ollama on the home network.

Phases 1–4 are sequential (each depends on the last); 5 and 6 can be reordered; the rest of Phase 8 is fair game any time.

## Building and testing

- Build and run from **Android Studio**, or from the command line with the Gradle wrapper (`.\gradlew.bat testDebugUnitTest` on Windows, `./gradlew` elsewhere). Gradle isn't installed globally; the wrapper downloads 8.7 itself.
- Gradle 8.7 can't run on JDK 25, which is the maintainer's default `java` *and* what Android Studio now bundles. Point `JAVA_HOME` at a JDK 17–21 first, e.g. `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-21.0.11"` in PowerShell. Don't hardcode that path in `gradle.properties`; it's machine-specific.
- `gradle.properties` forces IPv4 and raises HTTP timeouts because the first Gradle sync timed out downloading the distribution on this network. Keep those lines.
- Unit tests live in `app/src/test` and run on the JVM (`testDebugUnitTest`):
  - Pure logic with plain JUnit: `ConversationHistory`, `SenderAllowlist`, `SecretCipher` (software AES key), the AI clients' request building and response parsing, and `AiHttp` / `OpenAiClient` against a local `MockWebServer`.
  - Android-dependent code with Robolectric (SDK pinned in `src/test/resources/robolectric.properties`): `SettingsStore` including the plaintext-key migration, `ConversationRepository` on in-memory Room, the `SmsRelay` pipeline, `SmsReceiver` (fed real 3GPP PDUs built by `SmsPdu`), `BootReceiver`, `RelayService` and `MainActivity`.
  - Test seams are `internal var`s swapped in tests and restored in `@After`: `SettingsStore.secretKey` (Robolectric has no Keystore), and `SmsRelay.repositoryFor` / `clientFor` / `sendSms` (no real AI calls or texts). `ConversationRepository` also takes a DAO directly.
  - Still keep new logic in Android-free objects where possible; it's faster and simpler to test.
  - Not covered: the receiver's `goAsync()` fallback when a foreground-service start is refused, real Keystore behaviour, and actual SMS delivery. Those need a device.
  - **New code must be tested**: CI fails a PR when under 80% of the `app/src/main` lines it adds or changes are covered (JaCoCo, via `enableUnitTestCoverage` on the debug build, checked by `diff-cover` against the base branch). Robolectric-run code counts because the test task sets `isIncludeNoLocationClasses`. The `coverage-exempt` label skips the check for code that can't be unit-tested. Coverage report: `./gradlew createDebugUnitTestCoverageReport` → `app/build/reports/coverage/test/debug/`. Merge blocking itself depends on GitHub branch protection requiring the `test` check (see `RELEASING.md`).
- SMS behaviour must be tested on a **real phone with a SIM** — the emulator can receive simulated SMS but can't send real ones. Watch Logcat with the `SmsReceiver` tag.
- Versions are plain semver from 0.7.0 on (currently `0.7.0`, versionCode 7); the `-phaseN` suffix is gone now that builds are public. The release process (version bump, F-Droid changelog, tag) is in `RELEASING.md`.

## Key files (`app/src/main/java/com/smsairelay/app/`)

- `SmsReceiver.kt` — entry point: allowlist check, then hands off to `RelayService` (or runs `SmsRelay` itself as a fallback).
- `SmsRelay.kt` — the pipeline: reset keyword, history, AI call, reply SMS.
- `RelayService.kt` — long-running foreground service that runs `SmsRelay` and keeps the process alive.
- `BootReceiver.kt` — restarts `RelayService` after reboot or app update.
- `ConversationHistory.kt` — reset/trim policy (pure) and `ConversationRepository` (Room access).
- `ChatDatabase.kt` — Room entity, DAO and database.
- `AiClient.kt` — `AiClient` interface, `AiProvider` enum, shared HTTP helper, SMS system prompt. Providers: `ClaudeClient.kt`, `GeminiClient.kt`, `OpenAiClient.kt`.
- `SenderAllowlist.kt` — number parsing/matching; also produces the conversation key.
- `SecretCipher.kt` — AES-GCM encrypt/decrypt for API keys (pure) and `KeystoreSecretKey` (Keystore access).
- `SettingsStore.kt` / `SettingsActivity.kt` — persisted settings and the Settings screen.
- `MainActivity.kt` — SMS permission and battery-optimization status; starts `RelayService`.

## Conventions for future work in this repo

- No server-side code belongs in this repo — if a change seems to require one, that's a signal the design has drifted from the core decision above; reconsider before adding it.
- New providers go behind the `AiClient` interface; prefer an `OpenAiClient` instance with a different base URL over a new client class when the provider is OpenAI-compatible.
- Anything touching permissions, background execution, or notification behavior should be checked against current Android background-execution limits for the target API level — these change across Android versions and are the main source of real-world reliability bugs for this kind of app.
