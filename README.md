# Pocket Harness

> *"open youtube and play tu hi to hai"* — and it does.

A **pure-APK Android agent**. Type or speak a command in plain English and the
phone executes it. The rule-based brain runs fully on-device — no adb, no PC, no
server, no internet. Point it at an AI endpoint and it upgrades to a multi-step
planner with an optional screenshot-driven vision agent.

```
┌──────────────────────────────────────────────┐
│  Console                                     │
│  ┌────────────────────────────────────────┐  │
│  │ open youtube and play tu hi to hai     │  │
│  │                    [ mic ] [ Run ]     │  │
│  └────────────────────────────────────────┘  │
│                                              │
│  » open youtube and play tu hi to hai        │
│  ▸ opened YouTube                            │
│  ▸ asked YouTube to play "tu hi to hai"      │
│  · completed in 41 ms                        │
└──────────────────────────────────────────────┘
```

**Status:** `0.5-gold` (versionCode 6) — signed APK builds cleanly and ships in
this repo (`PocketHarness.apk`, ~253 KB). Core agent, AI planner, voice/wake
word, vision loop, and full UI are in. See [Roadmap](#roadmap) for what's next.

## Install

1. Copy `PocketHarness.apk` to your phone (WhatsApp/Drive/USB — anything).
2. Tap it → allow "install unknown apps" when prompted.
3. Open **Pocket Harness** and walk through onboarding: grant microphone, and
   optionally enable the Accessibility service (`Settings → Accessibility →
   Pocket Harness`) and "Modify system settings" (for brightness).
4. Works on Android 7.0+ (API 24). Accessibility features (taps, swipes,
   screenshots) need Android 11+ for the screenshot API.

No Play Store, no Gradle runtime, no external libraries — one dex, ~253 KB.

## Usage

Type a command in the Console and hit **Run command**, or tap the mic and talk.
Run history appears on the Console and in the **Runs** tab (searchable). The
**Skills** tab lists every verb with tap-to-preload examples.

### The grammar

| Say this | What happens |
|---|---|
| `open youtube` / `launch insta` | fuzzy-matches **every installed app** (typos OK, aliases like `yt`/`wa`/`tg`) |
| `play tu hi to hai` | `MEDIA_PLAY_FROM_SEARCH` intent → YouTube auto-plays |
| `play take five on spotify` | Spotify deep-link · also `on ytmusic` / `on youtube` |
| `volume up` · `set volume to 30%` · `mute` · `unmute` | AudioManager |
| `brightness 40%` · `max brightness` · `min brightness` | system settings (needs "Modify system settings") |
| `torch on` · `flashlight off` · `torch` | camera flash (bare `torch` toggles) |
| `call 9876543210` · `text mom saying omw` | dialer / SMS draft |
| `navigate to kempegowda airport` | Google Maps |
| `search for best filter coffee` · `google …` | Chrome → web search |
| `battery` · `time` · `camera` · `wifi settings` · `bluetooth settings` | quick answers / system panels |
| `share <text>` · `screenshot` · `help` | share sheet / capture / verb list |
| chain with `and` / `then` | multi-step plans |

Screen control (requires Accessibility service):

| Say this | What happens |
|---|---|
| `tap 500 300` | tap by 0–1000 normalized coordinates |
| `tap search` | heuristic tap (search bar / address bar) |
| `type <text>` · `press enter` | fill the focused field · submit (Android 11+) |
| `swipe up` / `swipe down` | scroll |
| `back` · `home` | global navigation actions |

Unknown input gets an honest `?` plus suggestions — the agent never silently
dead-ends.

### Voice

- **Hold-to-talk:** tap the mic; an in-app speech sheet opens (no system
  dialog), then the transcript runs.
- **Wake word (v0.4):** toggle `Settings → Wake word` and say
  `"hey harness"` — haptic + optional TTS confirms, then it listens for your
  command. Foreground-only; it pauses while hold-to-talk is active.
- **Spoken replies:** optional TTS confirmation per step (`Settings → Spoken replies`).

## AI planner

Optional. With an endpoint configured, commands are sent to an LLM that breaks
them into clauses, then the same on-device skills execute them. If the planner
fails for any reason, it falls back to the offline rule splitter.

### One-tap presets (`Settings → ai engine`)

| Preset | Endpoint | Transport | Default model |
|---|---|---|---|
| **ZEN** | `https://opencode.ai/zen/v1` | openai | `nemotron-3-ultra-free` |
| **OPENROUTER** | `https://openrouter.ai/api/v1` | openai | (pick one) |
| **GO** | `https://opencode.ai/zen/go/v1` | openai | `deepseek-v4-flash` |
| **SELF-HOST** | your LAN `opencode serve` | opencode | `provider/model` |

Settings also let you edit endpoint / model / api key manually, toggle the
transport (`openai-compatible` ↔ `opencode server api`), fetch models live from
the configured engine, and run **Test connection** (reachability, auth,
parsing).

Implementation details:

- **Two transports:** OpenAI-compatible `POST /chat/completions` and native
  opencode `POST /session` → `POST /session/{id}/message`.
- **URL rule:** endpoint ending in `/v1` → append `/chat/completions`; ending in
  `/chat/completions` → used as-is; anything else → `/v1/chat/completions`.
- **Stable session id:** generated per install and sent as
  `x-opencode-session` (required by OpenCode Go; without it Go returns
  `HTTP 400 MissingSessionID`).
- **Model dropdown:** `GET /models` (OpenAI transport) or `GET /config`
  (opencode transport), cached in preferences.
- **Never dead-ends:** unparseable planner output falls back to line splitting,
  then to the offline rules splitter.

Byte-for-byte curl equivalents of everything the app sends live in
[`API-CURL.md`](API-CURL.md).

## Vision agent

Toggle `Settings → Vision agent` (Android 11+ with Accessibility enabled) and
the app runs an observe → act loop instead of a fixed plan:

1. Screenshot the screen (overlay hidden) via `AccessibilityService.takeScreenshot`.
2. Send the 720px JPEG + goal + last 5 actions to the vision model.
3. The model replies with **exactly one** action: `open <app>`, `tap <x> <y>`
   (0–1000 coords), `type <text>`, `press enter`, `swipe up/down`, `back`,
   `home`, `wait`, `done`, or `fail <why>`.
4. Execute it, log it, repeat. Capped at **8 steps**
   (`VISION_MAX_STEPS` in `MainActivity`).

There's also a built-in path that auto-triggers the vision loop for
`open chrome and search for …` commands even when the toggle is off.

## Architecture

```
                      AI planner enabled?
 command ──▶ ┌───────────────┴───────────────┐
             │ no                            │ yes
             ▼                               ▼
     rules v1 (Harness)            AI planner (AIClient)
     offline · always on           opencode | openai transport
             │                               │ error → rules fallback
             └───────────────┬───────────────┘
                             ▼
                    skills execute (Harness)
                             │ vision toggle / chrome+search
                             ▼
          vision agent: screenshot → model → 1 action
          (HarnessService: gestures, text, capture)
```

| File | Role |
|---|---|
| `apk-project/src/com/pocketharness/MainActivity.java` | All UI (Console/Runs/Skills/Settings), onboarding, orchestration, voice + wake word, vision loop, run history (~2,500 lines) |
| `apk-project/src/com/pocketharness/Harness.java` | Offline rule engine: clause splitter → router → skill handlers (apps, media, audio, torch, comms, web, device) |
| `apk-project/src/com/pocketharness/AIClient.java` | HTTP layer: two planner transports, vision step calls, live model listing, JSON plan parsing |
| `apk-project/src/com/pocketharness/HarnessService.java` | AccessibilityService: taps, swipes, `ACTION_SET_TEXT`, back/home/IME-enter, screenshot → base64 |

Execution is off the UI thread (`ExecutorService`) so blocking skills never
trigger an ANR. History is capped at 50 entries in `SharedPreferences`.

## Permissions

| Permission | Why |
|---|---|
| `QUERY_ALL_PACKAGES` | fuzzy-launch any installed app |
| `WRITE_SETTINGS` | brightness (granted in onboarding) |
| `RECORD_AUDIO` | hold-to-talk + wake word |
| `VIBRATE` | haptic confirmation |
| `INTERNET` | AI planner (optional) |
| Accessibility service | screen gestures, text input, screenshots |
| `usesCleartextTraffic` | LAN `opencode serve` is plain http |

## Build from source

The toolchain is self-contained (JDK 17 + platform-34 + build-tools 34.0.0) and
lives in `../toolchain/`. The build is deliberately Gradle-free:
`aapt2 → javac → d8 → zipalign → apksigner`, so the whole pipeline is
inspectable in ~70 lines of shell.

```bash
./setup-toolchain.sh   # JDK 17 + platform-34 + build-tools (~400 MB, once)
./build.sh             # → PocketHarness.apk (signed, zipaligned)
```

- `setup-toolchain.sh` uses `sdkmanager` to fetch pieces into `../toolchain/`.
- `fetch-sdk-pieces.sh` is a fallback that downloads the platform/build-tools
  zips directly, bypassing `sdkmanager` entirely.
- `package.json` wires them up as `npm run setup` / `npm run build`.

Build artifacts land in `build/` (`unsigned.apk`, `classes.dex`, `full.apk`,
`aligned.apk`); the signed APK is written to `PocketHarness.apk` and signed with
the checked-in debug keystore.

## Development & testing

- **Fake engine (`fake_opencode_server.py`)** — speaks both transports on
  `0.0.0.0:8765` and always plans `["torch off", "time", "battery"]`:

  ```bash
  python3 fake_opencode_server.py
  # in the app: SELF-HOST preset → endpoint http://<lan-ip>:8765
  ```

- **Planner probe (`test_zen.py`)** — sends the exact planner prompt to the free
  Zen tier and prints raw + parsed reply, useful when tuning the prompt.

- **Automation via intents** — `MainActivity` accepts extras, so you can drive
  it from adb:

  ```bash
  adb install -r PocketHarness.apk
  adb shell am start -n com.pocketharness/.MainActivity \
    --es cmd "open youtube and play tu hi to hai"
  ```

  | Extra | Type | Effect |
  |---|---|---|
  | `cmd` | string | prefill and run a command |
  | `ai_ep`, `ai_model`, `ai_mode`, `ai_key` | string | update planner config |
  | `ai_on` | bool | enable/disable the AI planner |
  | `ai_models` | bool | open Settings + model picker |
  | `ai_test` | bool | open Settings + run connection test |

- **Design reference** — `design/mockups.html` plus iteration screenshots from
  the UI build-out (including network/planner proof shots) live in `design/`.

## Project layout

```
pocket-harness/
├── PocketHarness.apk          # signed build (0.5-gold)
├── build.sh                   # aapt2 → javac → d8 → zipalign → apksigner
├── setup-toolchain.sh         # one-time SDK/JDK download
├── fetch-sdk-pieces.sh        # sdkmanager-free SDK fallback
├── fake_opencode_server.py    # dual-transport test server
├── test_zen.py                # planner prompt probe
├── API-CURL.md                # exact HTTP contracts
├── apk-project/               # manifest, res, java sources
├── design/                    # mockups + iteration screenshots
└── build/                     # intermediate artifacts
```

## Roadmap

- Generalized vision planning (the loop exists; auto-trigger is currently the
  Chrome/search path)
- Contact lookup (`READ_CONTACTS`) and richer SMS flows
- Floating bubble overlay / true background wake word
- Screenshot support below API 30 (currently shell `screencap` best-effort)

## License

MIT — see `package.json` (`"license": "MIT"`).
