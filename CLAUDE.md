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

- **Language**: Kotlin, min SDK 26.
- **Background trigger**: manifest-registered `BroadcastReceiver` for `android.provider.Telephony.SMS_RECEIVED` (exempted from Android 8+ implicit-broadcast background restrictions, so it fires even when the app isn't running). `onReceive()` calls `goAsync()` and hands off immediately — no blocking work in the receiver itself.
- **Processing**: a foreground service (persistent low-priority notification) does the actual work — DB read, API call, DB write, SMS send — so it isn't killed mid-flight by Doze/App Standby.
- **Persistence**: Room (SQLite), on-device only. No external DB, no sync.
  - `conversations` / `messages` tables keyed by sender phone number.
  - Tracks `lastActivityAt` and a running token-estimate per conversation for reset logic.
- **AI client**: plain OkHttp + `org.json` (bundled with Android), direct HTTPS calls to the provider the user picks in Settings — Claude (Messages API), Gemini or the open-weight Gemma models (both via Google AI Studio's `generateContent`, sharing one key through `AiProvider.keySlot`), or OpenAI (Chat Completions). Each is one `AiClient` implementation (`AiClient.kt`); API key and optional model override are stored per provider. OpenAI uses Chat Completions (not the Responses API) because Groq, OpenRouter, Mistral, Cerebras etc. accept the same shape, so they can be added as `OpenAiClient` instances with a different base URL. Retrofit was considered but dropped — a handful of single endpoints isn't worth the converter/interface machinery.
- **Secrets**: API key stored in `EncryptedSharedPreferences` (Android Keystore-backed), entered by the user in a Settings screen — never hardcoded, never bundled, never leaves the device except in the auth header of requests to the selected provider.
- **Sending SMS**: `SmsManager.sendMultipartTextMessage()` for replies that exceed one segment.
- **Reliability**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemption flow, `BOOT_COMPLETED` receiver to re-arm after reboot.

## Session / context reset policy

The original pain point this project exists to solve (context window growth + drift over a long-lived SMS thread), handled entirely on-device, no scheduler process needed — evaluated at message-receive time:

1. **Inactivity timeout** (primary mechanism) — if idle longer than a configurable threshold (default ~45 min), start a fresh history instead of appending.
2. **Explicit keyword** — user can text `/new` or `RESET` at any time to force a fresh session regardless of timers.
3. **Token-budget sliding window** — rough token estimate (`chars / 4`) tracked per conversation; when it crosses a threshold, oldest turns are dropped first; a hard reset happens only if trimming isn't enough.
4. The system prompt (SMS tone/length instructions) is stored separately from rolling history so a reset never loses it.

## Security model

Even though there's no shared key or multi-tenant abuse surface to worry about, the app is still reachable by SMS from *anyone who has the number* — so:

- **Sender allowlist** (Settings) — only phone numbers the owner explicitly adds get a response; everything else is dropped silently (logged locally, no reply sent). This protects the owner's own API spend and inbox, not other users.
- API key never stored in plaintext (`EncryptedSharedPreferences` / Keystore).
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

Full interactive version: the "Relay Roadmap" artifact from project planning. Summary, in order:

1. **Bare Signal** — permissions, receiver, hardcoded echo reply. Proves the OS will let this app catch and send SMS.
2. **First Real Reply** — wire in the Claude API, single-turn, no memory yet.
3. **Memory** — Room-backed per-sender conversation history, multi-turn context.
4. **Knowing When to Forget** — inactivity timeout, keyword reset, sliding-window trim.
5. **Locking the Door** — sender allowlist, encrypted API key storage.
6. **Staying Alive** — battery exemption, boot receiver, foreground service hardening.
7. **Out the Door** — README, license, CI-built signed release APK, F-Droid metadata.
8. **Bring Your Own AI** — provider interface with Claude, Gemini, Gemma (Google AI Studio) and OpenAI done early (cloud APIs only, no on-device models); next is OpenAI-compatible free providers (Groq, OpenRouter, etc.).

Phases 1–4 are sequential (each depends on the last); 5 and 6 can be reordered; the stretch goal is fair game any time after v1 ships.

## Conventions for future work in this repo

- No server-side code belongs in this repo — if a change seems to require one, that's a signal the design has drifted from the core decision above; reconsider before adding it.
- New providers go behind the `AiClient` interface; prefer an `OpenAiClient` instance with a different base URL over a new client class when the provider is OpenAI-compatible.
- Anything touching permissions, background execution, or notification behavior should be checked against current Android background-execution limits for the target API level — these change across Android versions and are the main source of real-world reliability bugs for this kind of app.
