#!/usr/bin/env python3
"""Test the planner prompt against Zen free tier — no auth needed."""
import json, urllib.request

BODY = {
    "model": "nemotron-3-ultra-free",
    "temperature": 0,
    "messages": [
        {
            "role": "user",
            "content": (
                "You are the planner inside PocketHarness, an on-device phone agent.\n"
                "Split the user request into minimal executable steps.\n"
                "Each step must be ONE short clause using ONLY these verbs:\n"
                "- open/launch <app>\n"
                "- play <song> [on youtube|ytmusic|spotify]\n"
                "- volume up | down | mute | unmute | set volume to N%\n"
                "- brightness N% | max brightness | min brightness\n"
                "- torch on | torch off\n"
                "- call <number> | text <number> saying <msg>\n"
                "- navigate to <place> | search for <query> | google <query>\n"
                "- camera | battery | time | date | wifi settings | bluetooth settings\n"
                "- share <text>\n"
                "Reply with ONLY a JSON array of step strings. No prose, no markdown fences.\n\n"
                'User request: "open youtube and play tu hi to hai on ytmusic then set volume to 60%"'
            ),
        }
    ],
}

req = urllib.request.Request(
    "https://opencode.ai/zen/v1/chat/completions",
    data=json.dumps(BODY).encode(),
    headers={"Content-Type": "application/json", "Connection": "close", "User-Agent": "curl/8.5.0"},
)
with urllib.request.urlopen(req, timeout=30) as resp:
    r = json.loads(resp.read())

content = r["choices"][0]["message"]["content"]
print("RAW:", repr(content))
try:
    arr = json.loads(content)
    print("PARSED:", arr)
except Exception as e:
    print("PARSE ERR:", e)
    lines = [l.strip() for l in content.split("\n") if l.strip()]
    print("LINES:", lines)
