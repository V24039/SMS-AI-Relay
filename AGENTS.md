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
   → Room DB: load conversation history for that sender number
   → reset policy evaluated (inactivity timeout / keyword / token budget)
   → selected AI provider (Claude / Gemini / Gemma / OpenAI) called with system prompt + history + new message
   → response appended to history in Room
   → SmsManager sends the reply (multipart if long)
```

No network component other than the outbound HTTPS call to the AI provider. No component the app doesn't own runs on any other machine.

## Tech stack

Items marked *(planned)* describe the target design and are **not built yet**. Don't assume they exist.

- **Language**: Kotlin, min SDK 26, target/compile SDK 34.
- **Background trigger**: manifest-registered `BroadcastReceiver` for `android.provider.Telephony.SMS_RECEIVED` (exempted from Android 8+ implicit-broadcast background restrictions, so it fires even when the app isn't running). `onReceive()` checks the allowlist first, then calls `goAsync()` and hands off to a coroutine on `Dispatchers.IO` — no blocking work on the receiver's thread.
- **Processing**: currently the whole pipeline (DB read, API call, DB write, SMS send) runs in that coroutine inside the ~10s `goAsync()` window. *(planned, Phase 6)* Move it into a foreground service with a persistent low-priority notification so it isn't killed mid-flight by Doze/App Standby.
- **Persistence**: Room (SQLite), on-device only. No external DB, no sync.
  - One `messages` table keyed by the normalised sender number (`SenderAllowlist.conversationKey`).
  - Last activity is the newest message timestamp and the token estimate is computed at load time, so neither is stored separately.
- **AI client**: plain OkHttp + `org.json` (bundled with Android), direct HTTPS calls to the provider the user picks in Settings — Claude (Messages API), Gemini or the open-weight Gemma models (both via Google AI Studio's `generateContent`, sharing one key through `AiProvider.keySlot`), or OpenAI (Chat Completions). Each is one `AiClient` implementation (`AiClient.kt`); API key and optional model override are stored per provider. OpenAI uses Chat Completions (not the Responses API) because Groq, OpenRouter, Mistral, Cerebras etc. accept the same shape, so they can be added as `OpenAiClient` instances with a different base URL. Retrofit was considered but dropped — a handful of single endpoints isn't worth the converter/interface machinery.
- **Settings/secrets**: entered by the user on the Settings screen and stored by `SettingsStore` — provider, per-provider API key and model override, allowlist, idle timeout. Keys are never hardcoded or bundled and only leave the device in the auth header of requests to the selected provider. Currently **plaintext** `SharedPreferences`; *(planned, Phase 5)* move to `EncryptedSharedPreferences` (Android Keystore-backed).
- **Backup**: `allowBackup="false"` plus `data_extraction_rules.xml` excluding every domain, so keys, allowlist and history never leave the device via cloud backup or device-to-device transfer.
- **Sending SMS**: `SmsManager.sendMultipartTextMessage()` for replies that exceed one segment.
- **Reliability** *(planned, Phase 6)*: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemption flow, `BOOT_COMPLETED` receiver to re-arm after reboot.

## Session / context reset policy

The original pain point this project exists to solve (context window growth + drift over a long-lived SMS thread), handled entirely on-device, no scheduler process needed — evaluated at message-receive time. Logic and constants live in `ConversationHistory.kt`:

1. **Explicit keyword** — a message that is exactly `/new` or `RESET` (trimmed, any case) deletes that sender's history and replies "New conversation started." without calling the AI, so it works even with no API key set. Longer messages containing the word go to the AI as normal.
2. **Inactivity timeout** — default 45 min, editable in Settings (invalid input falls back to the default). On every incoming message, *all* conversations idle past the timeout are deleted, not just the sender's.
3. **Token budget** — each request carries only the newest turns fitting ~4000 estimated tokens (`chars / 4`). The new message is always sent even if it alone exceeds the budget. Leading assistant turns left by trimming are dropped, since providers need the conversation to start with a user turn.
4. **Storage cap** — at most 100 rows per sender; older rows are trimmed after each save.
5. A user/assistant pair is saved only after the model answers, so a failed call never leaves an unanswered user turn in history.
6. The system prompt (`SMS_SYSTEM_PROMPT` in `AiClient.kt`) is never stored in history, so a reset never loses it.

## Security model

Even though there's no shared key or multi-tenant abuse surface to worry about, the app is still reachable by SMS from *anyone who has the number* — so:

- **Sender allowlist** (Settings, built) — only phone numbers the owner explicitly adds get a response; everything else is dropped silently before any API call or SMS is spent. An empty allowlist replies to nobody. Numbers match on their last 10 digits, so `+91 98765 43210` and `098765 43210` are the same sender; shortcodes and alphanumeric sender IDs never match. This protects the owner's own API spend and inbox, not other users.
- API key encrypted at rest *(planned, Phase 5 — currently plaintext)*.
- No telemetry, no analytics, no third-party network calls other than the configured AI provider's API.

## Distribution

`RECEIVE_SMS` / `SEND_SMS` are restricted permissions under Google Play policy (generally Play-approved only for declared default-SMS-handler apps). This project is **not** going to become a full default SMS app, so:

- Ship via **GitHub Releases** (signed APK) and optionally **F-Droid**.
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
5. **Locking the Door** — sender allowlist *(done)*, encrypted API key storage *(not started)*.
6. **Staying Alive** — battery exemption, boot receiver, foreground service hardening. *Not started.*
7. **Out the Door** — README, license, CI-built signed release APK, F-Droid metadata. *Not started.*
8. **Bring Your Own AI** — provider interface with Claude, Gemini, Gemma (Google AI Studio) and OpenAI *done early* (cloud APIs only, no on-device models); next is OpenAI-compatible free providers (Groq, OpenRouter, etc.).

Phases 1–4 are sequential (each depends on the last); 5 and 6 can be reordered; the rest of Phase 8 is fair game any time.

## Building and testing

- Build and run from **Android Studio**. Command-line Gradle on the maintainer's machine fails because the default `java` is JDK 25, which Gradle 8.7 can't run on — use Android Studio's configured Gradle JDK.
- `gradle.properties` forces IPv4 and raises HTTP timeouts because the first Gradle sync timed out downloading the distribution on this network. Keep those lines.
- Unit tests live in `app/src/test` and cover the pure logic: `ConversationHistory`, `SenderAllowlist`, and the AI clients' request building and response parsing. Keep new logic in Android-free objects like these so it stays testable on the JVM.
- SMS behaviour must be tested on a **real phone with a SIM** — the emulator can receive simulated SMS but can't send real ones. Watch Logcat with the `SmsReceiver` tag.
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` per phase (currently `0.4.0-phase4`).

## Key files (`app/src/main/java/com/smsairelay/app/`)

- `SmsReceiver.kt` — entry point: allowlist check, reset keyword, history, AI call, reply.
- `ConversationHistory.kt` — reset/trim policy (pure) and `ConversationRepository` (Room access).
- `ChatDatabase.kt` — Room entity, DAO and database.
- `AiClient.kt` — `AiClient` interface, `AiProvider` enum, shared HTTP helper, SMS system prompt. Providers: `ClaudeClient.kt`, `GeminiClient.kt`, `OpenAiClient.kt`.
- `SenderAllowlist.kt` — number parsing/matching; also produces the conversation key.
- `SettingsStore.kt` / `SettingsActivity.kt` — persisted settings and the Settings screen.

## Conventions for future work in this repo

- No server-side code belongs in this repo — if a change seems to require one, that's a signal the design has drifted from the core decision above; reconsider before adding it.
- New providers go behind the `AiClient` interface; prefer an `OpenAiClient` instance with a different base URL over a new client class when the provider is OpenAI-compatible.
- Anything touching permissions, background execution, or notification behavior should be checked against current Android background-execution limits for the target API level — these change across Android versions and are the main source of real-world reliability bugs for this kind of app.
