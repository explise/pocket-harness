# PocketHarness → engine: exact cURL equivalents

These are byte-for-byte what `AIClient` sends (headers included:
`Content-Type: application/json`, `Accept: application/json`,
`Connection: close`, `User-Agent: PocketHarness/0.5 (+https://github.com/anomalyco/pocket-harness)`,
`x-opencode-session: <stable id>` (required by OpenCode Go — see
<https://opencode.ai/docs/go#where-can-i-use-it>; without it Go returns
`HTTP 400 {"type":"error","error":{"type":"MissingSessionID",...}}`),
plus `Authorization: Bearer <key>` whenever a key is set). The session id is
generated once per install and stored in SharedPreferences (`ai_session`).

---

## The planner prompt (what gets sent as the message)

```
You are the planner inside PocketHarness, an on-device phone agent.
Split the user request into minimal executable steps.
Each step must be ONE short clause using ONLY these verbs:
- open/launch <app>
- play <song> [on youtube|ytmusic|spotify]
- volume up | down | mute | unmute | set volume to N%
- brightness N% | max brightness | min brightness
- torch on | torch off
- call <number> | text <number> saying <msg>
- navigate to <place> | search for <query> | google <query>
- camera | battery | time | date | wifi settings | bluetooth settings
- share <text>
Reply with ONLY a JSON array of step strings. No prose, no markdown fences.

User request: "<your command>"
```

Note: the planner prompt also appends
`Apps installed on this phone: <comma-separated labels>` so it can choose real
apps instead of falling back to a web search.

---

## 1) OPENAI-compatible transport (Zen / OpenRouter / LM Studio / Ollama…)

### Model dropdown
```bash
curl https://openrouter.ai/api/v1/models \
  -H "Authorization: Bearer $API_KEY" \
  -H "User-Agent: PocketHarness/0.5 (+https://github.com/anomalyco/pocket-harness)"

# OpenCode Go (Zen Go) — custom User-Agent + x-opencode-session required
# per https://opencode.ai/docs/go#where-can-i-use-it
curl https://opencode.ai/zen/go/v1/models \
  -H "Authorization: Bearer $OPENCODE_GO_KEY" \
  -H "User-Agent: PocketHarness/0.5 (+https://github.com/anomalyco/pocket-harness)" \
  -H "x-opencode-session: $POCKET_SESSION"
# → {"object":"list","data":[{"id":"deepseek-v4-pro"}, {"id":"deepseek-v4-flash"}, {"id":"deepseek-v4-flash-vision-exp"}, ...]}
# deepseek + all Go models appear here; the app's GO preset (endpoint https://opencode.ai/zen/go/v1, mode openai) uses this list
```

### Test connection & every real run
```bash
CMD='open youtube and play tu hi to hai'

curl -X POST https://openrouter.ai/api/v1/chat/completions \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Connection: close" \
  -H "User-Agent: PocketHarness/0.5 (+https://github.com/anomalyco/pocket-harness)" \
  -d @- <<EOF
{
  "model": "openai/gpt-4o-mini",
  "temperature": 0,
  "messages": [
    {
      "role": "user",
      "content": "You are the planner inside PocketHarness, an on-device phone agent.\nSplit the user request into minimal executable steps.\nEach step must be ONE short clause using ONLY these verbs:\n- open/launch <app>\n- play <song> [on youtube|ytmusic|spotify]\n- volume up | down | mute | unmute | set volume to N%\n- brightness N% | max brightness | min brightness\n- torch on | torch off\n- call <number> | text <number> saying <msg>\n- navigate to <place> | search for <query> | google <query>\n- camera | battery | time | date | wifi settings | bluetooth settings\n- share <text>\nReply with ONLY a JSON array of step strings. No prose, no markdown fences.\n\nUser request: \"$CMD\""
    }
  ]
}
EOF
```

Notes:
- URL building rule: endpoint ending in `/v1` → append `/chat/completions`;
  already ending in `/chat/completions` → used as-is; anything else → `/v1/chat/completions`.
- `model` = your model setting **minus** any `provider/` prefix (that prefix
  is only meaningful in opencode mode).
- App expects `choices[0].message.content` back.

---

## 2) OPENCODE native transport (`opencode serve`)

### Model dropdown
```bash
curl http://127.0.0.1:4096/config
```
(Use the host/port your `opencode serve` prints. App flattens
`providers → models` into `provider/model` entries.)

### Test connection & every real run — two calls

**Step 1 — create session:**
```bash
SID=$(curl -s -X POST http://127.0.0.1:4096/session \
  -H "Content-Type: application/json" \
  -H "Connection: close" \
  -H "User-Agent: PocketHarness/0.5 (+https://github.com/anomalyco/pocket-harness)" \
  -d '{"title":"pocket-harness"}' | jq -r .id)
```

**Step 2 — send the plan request:**
```bash
CMD='open youtube and play tu hi to hai'

curl -X POST "http://127.0.0.1:4096/session/$SID/message" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Connection: close" \
  -H "User-Agent: PocketHarness/0.5 (+https://github.com/anomalyco/pocket-harness)" \
  -d @- <<EOF
{
  "providerID": "anthropic",
  "modelID": "claude-sonnet-4-20250514",
  "parts": [
    {
      "type": "text",
      "text": "You are the planner inside PocketHarness, an on-device phone agent.\nSplit the user request into minimal executable steps.\nEach step must be ONE short clause using ONLY these verbs:\n- open/launch <app>\n- play <song> [on youtube|ytmusic|spotify]\n- volume up | down | mute | unmute | set volume to N%\n- brightness N% | max brightness | min brightness\n- torch on | torch off\n- call <number> | text <number> saying <msg>\n- navigate to <place> | search for <query> | google <query>\n- camera | battery | time | date | wifi settings | bluetooth settings\n- share <text>\nReply with ONLY a JSON array of step strings. No prose, no markdown fences.\n\nUser request: \"$CMD\""
    }
  ]
}
EOF
```

Notes:
- Your Settings model string splits on the FIRST `/`: `anthropic/claude-sonnet-4`
  → `providerID=anthropic`, `modelID=claude-sonnet-4`. No slash = providerID sent empty.
- App reads the first `parts[i]` where `type=="text"` from the reply
  (also checks `info.parts[0]` for builds shaped that way).
- Session id comes from `.id` of the `/session` reply.

---

## Reply contract (both transports)

Whatever comes back must contain (after light cleanup) a JSON array of clauses:

```json
["open youtube", "play tu hi tohai on ytmusic"]
```

If the reply isn't parseable as JSON, the app falls back to splitting the
planner's raw lines; if THAT fails too, it falls back to the offline rules
splitter of the original command. It never dead-ends.
