package com.pocketharness;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI planner client for PocketHarness.
 *
 * Speaks TWO transports, selected in Settings:
 *
 *   1. "opencode"  — a running opencode server (sst/opencode):
 *        POST {base}/session                      -> {"id":"ses_..."}
 *        POST {base}/session/{id}/message         -> assistant message
 *            body: {"providerID":"anthropic","modelID":"...",
 *                   "parts":[{"type":"text","text":prompt}]}
 *        Model setting format: "providerID/modelID"
 *
 *   2. "openai"    — any OpenAI-compatible chat endpoint
 *        (llama.cpp server, LM Studio, Ollama, opencode zen gateway, proxies):
 *        POST {base}/chat/completions             (auto-prepends /v1 when missing)
 *        Authorization: Bearer <key>              (when a key is configured)
 *
 * Zero dependencies: HttpURLConnection + org.json only.
 * All network work happens on a single background thread; results arrive
 * on the caller's callback (post to UI thread yourself).
 */
public class AIClient {

    public interface Callback {
        /** planner replied with executable clauses */
        void onPlan(List<String> clauses, long ms);
        /** network / auth / parse failure — caller should fall back to rules */
        void onError(String msg, long ms);
    }

    /** immutable snapshot of the engine config */
    public static class Cfg {
        public boolean enabled = false;
        public String  mode     = "opencode";   // "opencode" | "openai"
        public String  endpoint = "";
        public String  model    = "";
        public String  apiKey   = "";
        public String  session  = "";           // stable id for x-opencode-session (opencode Go)

        public boolean ready() {
            return enabled && !endpoint.trim().isEmpty()
                    && !model.trim().isEmpty()
                    && (mode.equals("openai") || model.contains("/"));
        }

        public String shortModel() {
            String m = model.trim();
            int slash = m.indexOf('/');
            return slash >= 0 ? m.substring(slash + 1) : m;
        }
    }

    public static Cfg load(SharedPreferences sp) {
        Cfg c = new Cfg();
        c.enabled  = sp.getBoolean("ai_on", false);
        c.mode     = sp.getString("ai_mode", "opencode");
        c.endpoint = sp.getString("ai_ep", "").trim();
        c.model    = sp.getString("ai_model", "").trim();
        c.apiKey   = sp.getString("ai_key", "").trim();
        // opencode Go requires a stable session id per conversation in x-opencode-session.
        c.session  = sp.getString("ai_session", "");
        if (c.session.isEmpty()) {
            c.session = "ph-" + java.util.UUID.randomUUID().toString().replace("-", "");
            sp.edit().putString("ai_session", c.session).apply();
        }
        return c;
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private static final int CONNECT_TO = 8000;
    private static final int READ_TO    = 45000;
    private static final int MAX_STEPS  = 10;

    // OpenCode Go tracking: properly identify harness per https://opencode.ai/docs/go#where-can-i-use-it
    // (no broad/default Java UA). Keep this stable so Go can attribute traffic & avoid flagging.
    private static final String USER_AGENT = "PocketHarness/0.5 (+https://github.com/anomalyco/pocket-harness)";

    /** fire an async planning request */
    public static void plan(final Cfg cfg, final String cmd, final Callback cb) {
        plan(cfg, cmd, null, cb);
    }

    /** same, with the installed-app list so the planner can pick real apps */
    public static void plan(final Cfg cfg, final String cmd, final java.util.List<String> apps, final Callback cb) {
        final String prompt = buildPrompt(cmd, apps);
        POOL.execute(new Runnable() { public void run() {
            long t0 = SystemClock.elapsedRealtime();
            android.util.Log.i("PH_AI", "planning via " + cfg.mode + " -> "
                    + cfg.endpoint + " model=" + cfg.model + " apps=" + (apps == null ? 0 : apps.size()));
            try {
                String reply = "opencode".equals(cfg.mode)
                        ? viaOpenCode(cfg, prompt)
                        : viaOpenAI(cfg, prompt);
                long ms = Math.max(1, SystemClock.elapsedRealtime() - t0);
                List<String> clauses = parsePlan(reply, cmd);
                android.util.Log.i("PH_AI", "plan OK in " + ms + " ms: " + clauses);
                cb.onPlan(clauses, ms);
            } catch (Exception e) {
                long ms = Math.max(1, SystemClock.elapsedRealtime() - t0);
                android.util.Log.w("PH_AI", "plan FAILED after " + ms + " ms", e);
                cb.onError(friendly(e), ms);
            }
        }});
    }

    public interface ModelsCallback {
        void onModels(List<String> ids);
        void onError(String msg);
    }

    // ---- observe → act loop: one action per screenshot ----

    public interface StepCb {
        /** single next action, e.g. "tap 500 150" / "type cat" / "done" / "fail reason" */
        void onAction(String action, long ms);
        void onError(String msg, long ms);
    }

    /** send a screenshot + a custom agent prompt, return exactly one next action. */
    public static void stepWithImage(final Cfg cfg, final String prompt,
                                     final String base64Jpeg, final StepCb cb) {
        POOL.execute(new Runnable() { public void run() {
            long t0 = SystemClock.elapsedRealtime();
            try {
                String reply = viaOpenAIVision(cfg, prompt, base64Jpeg);
                long ms = Math.max(1, SystemClock.elapsedRealtime() - t0);
                String action = parseAction(reply);
                android.util.Log.i("PH_AI", "vision step (" + ms + " ms): " + action);
                cb.onAction(action, ms);
            } catch (Exception e) {
                long ms = Math.max(1, SystemClock.elapsedRealtime() - t0);
                android.util.Log.w("PH_AI", "vision step FAILED after " + ms + " ms", e);
                cb.onError(friendly(e), ms);
            }
        }});
    }

    /** pull the first action out of the model's reply (JSON array preferred). */
    static String parseAction(String reply) {
        if (reply == null) return "";
        String s = reply.trim();
        try {
            String arr = s.replaceFirst("(?s)^.*?\\[", "[").replaceFirst("(?s)\\].*$", "]");
            JSONArray a = new JSONArray(arr);
            if (a.length() > 0) {
                String first = String.valueOf(a.opt(0)).trim();
                if (looksLikeAction(first)) return first;
            }
        } catch (Exception ignored) { }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\[\"([^\"]{1,120})\"\\]").matcher(s);
        String last = null;
        while (m.find()) { last = m.group(1); }
        if (looksLikeAction(last)) return last.trim();
        for (String ln : s.split("\n")) {
            String t = ln.trim().replaceFirst("^[-*•\\d.)\\s]+", "").trim();
            if (looksLikeAction(t)) return t;
        }
        return "";
    }

    private static boolean looksLikeAction(String s) {
        return s != null && s.toLowerCase(java.util.Locale.US)
                .matches("^(open|tap|type|press|swipe|back|home|wait|done|fail|click|select|choose)\\b.*");
    }

    /** fetch selectable models from the configured engine.
     *  openai-compat: GET {base}/v1/models        -> {"data":[{"id":..}]}
     *  opencode:      GET {base}/config           -> providers -> models tree */
    public static void listModels(final Cfg cfg, final ModelsCallback cb) {
        POOL.execute(new Runnable() { public void run() {
            try {
                List<String> out = "opencode".equals(cfg.mode)
                        ? fetchOpenCodeModels(cfg)
                        : fetchOpenAIModels(cfg);
                if (out.isEmpty()) throw new Exception("server returned no models");
                java.util.Collections.sort(out);
                android.util.Log.i("PH_AI", "models fetched: " + out.size());
                cb.onModels(out);
            } catch (Exception e) {
                android.util.Log.w("PH_AI", "model list failed", e);
                cb.onError(friendly(e));
            }
        }});
    }

    private static List<String> fetchOpenAIModels(Cfg cfg) throws Exception {
        String arr = httpGet(openaiBase(cfg.endpoint) + "/models", cfg.apiKey, cfg.session);
        List<String> out = new ArrayList<>();
        JSONObject o = new JSONObject(arr);
        JSONArray data = o.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                String id = m == null ? String.valueOf(data.opt(i)) : m.optString("id", "");
                if (!id.isEmpty()) out.add(id);
            }
        }
        return out;
    }

    private static List<String> fetchOpenCodeModels(Cfg cfg) throws Exception {
        String base = trimSlash(cfg.endpoint);
        List<String> out = new ArrayList<>();
        // Go route: try /config, then /go/config, then openai shape
        try {
            JSONObject conf = new JSONObject(httpGet(base + "/config", cfg.apiKey, cfg.session));
            JSONObject provs = conf.optJSONObject("providers");
            if (provs != null) {
                java.util.Iterator<String> it = provs.keys();
                while (it.hasNext()) {
                    String pid = it.next();
                    JSONObject pv = provs.optJSONObject(pid);
                    if (pv == null) continue;
                    JSONObject models = pv.optJSONObject("models");
                    if (models != null) {
                        java.util.Iterator<String> mi = models.keys();
                        while (mi.hasNext()) out.add(pid + "/" + mi.next());
                    }
                }
            }
            android.util.Log.i("PH_AI", "config-parse models so far: " + out.size());
        } catch (Exception e) {
            android.util.Log.w("PH_AI", "config parse failed: " + e);
        }
        if (out.isEmpty()) {
            // Go route fallback: try /go/config
            try {
                JSONObject conf2 = new JSONObject(httpGet(base + "/go/config", cfg.apiKey, cfg.session));
                JSONObject provs2 = conf2.optJSONObject("providers");
                if (provs2 != null) {
                    java.util.Iterator<String> it = provs2.keys();
                    while (it.hasNext()) {
                        String pid = it.next();
                        JSONObject pv = provs2.optJSONObject(pid);
                        if (pv == null) continue;
                        JSONObject models = pv.optJSONObject("models");
                        if (models != null) {
                            java.util.Iterator<String> mi = models.keys();
                            while (mi.hasNext()) out.add(pid + "/" + mi.next());
                        }
                    }
                }
                android.util.Log.i("PH_AI", "go/config models: " + out.size());
            } catch (Exception e3) {
                android.util.Log.w("PH_AI", "go/config failed: " + e3);
            }
        }
        if (out.isEmpty()) {
            // some builds expose an openai-shaped list too (including Go)
            try {
                java.util.List<String> bare = fetchOpenAIModels(cfg);
                // For opencode mode, bare ids (e.g. "deepseek-v4-pro") don't satisfy ready() which wants "provider/model".
                // Prefix with the right provider so the picker shows usable entries and viaOpenCode can split.
                if ("opencode".equals(cfg.mode) && !bare.isEmpty() && bare.get(0).indexOf('/') < 0) {
                    boolean isGo = trimSlash(cfg.endpoint).contains("/zen/go") || trimSlash(cfg.endpoint).contains("/go/");
                    String prefix = isGo ? "opencode-go/" : "opencode/";
                    for (String id : bare) out.add(prefix + id);
                    android.util.Log.i("PH_AI", "prefixed bare models for opencode mode: " + out.size());
                } else {
                    out.addAll(bare);
                }
            } catch (Exception e2) {
                android.util.Log.w("PH_AI", "openai-shape fallback failed: " + e2);
            }
        }
        return out;
    }

    /** normalize an openai-compatible endpoint to its /v1 base */
    private static String openaiBase(String endpoint) {
        String u = trimSlash(endpoint);
        if (u.endsWith("/chat/completions"))
            u = u.substring(0, u.length() - "/chat/completions".length());
        if (!u.endsWith("/v1")) u = u + "/v1";
        return u;
    }

    private static String httpGet(String url, String key, String session) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TO);
            conn.setReadTimeout(READ_TO);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            applySession(conn, session);
            if (key != null && !key.isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + key);
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = is == null ? "" : slurp(is);
            if (code >= 400) throw new Exception("HTTP " + code + snippet(text));
            return text;
        } finally {
            conn.disconnect();
        }
    }

    /** quick health probe used by Settings → “Test connection”:
     *  a successful plan("torch on") proves reachability, auth and parsing. */
    public static void test(final Cfg cfg, final Callback cb) {
        plan(cfg, "torch on", cb);
    }

    // ------------------------------------------------------------------ prompt

    private static String buildPrompt(String cmd, java.util.List<String> apps) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the planner inside PocketHarness, an on-device phone agent.\n");
        sb.append("Split the user request into minimal executable steps.\n");
        sb.append("Each step must be ONE short clause using ONLY these verbs:\n");
        sb.append("- open/launch <app>            (prefer one of the installed apps listed below)\n");
        sb.append("- play <song> [on youtube|ytmusic|spotify]\n");
        sb.append("- volume up | down | mute | unmute | set volume to N%\n");
        sb.append("- brightness N% | max brightness | min brightness\n");
        sb.append("- torch on | torch off\n");
        sb.append("- call <number> | text <number> saying <msg>\n");
        sb.append("- navigate to <place> | search for <query> | google <query>\n");
        sb.append("- camera | battery | time | date | wifi settings | bluetooth settings\n");
        sb.append("- share <text>\n");
        sb.append("Reply with ONLY a JSON array of step strings. No prose, no markdown fences.\n");
        if (apps != null && !apps.isEmpty()) {
            sb.append("\nApps installed on this phone:\n");
            for (int i = 0; i < apps.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(apps.get(i));
            }
            sb.append("\n");
        }
        sb.append("\nUser request: \"").append(cmd.trim()).append("\"");
        return sb.toString();
    }

    /** robustly extract step list from whatever the model muttered */
    static List<String> parsePlan(String reply, String original) throws Exception {
        if (reply == null) throw new Exception("empty reply");
        String s = reply.trim()
                .replaceFirst("(?s)^.*?\\[", "[").replaceFirst("(?s)\\].*$", "]");
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length() && out.size() < MAX_STEPS; i++) {
                String st = String.valueOf(arr.opt(i)).trim();
                if (!st.isEmpty()) out.add(st);
            }
        } catch (Exception je) {
            // fallback: one step per line, bullets/numbers stripped
            for (String ln : reply.split("\n")) {
                if (out.size() >= MAX_STEPS) break;
                String st = ln.trim().replaceFirst("^[-*•\\d.)\\s]+", "").trim();
                if (!st.isEmpty()) out.add(st);
            }
        }
        if (out.isEmpty()) {
            // last resort: let the offline rules split it
            for (String c : original.trim().split("\\s+(?:and|then)\\s+"))
                if (!c.trim().isEmpty()) out.add(c.trim());
        }
        return out;
    }

    // --------------------------------------------------------------- transport

    /** opencode server: create a session, post the planning message */
    private static String viaOpenCode(Cfg cfg, String prompt) throws Exception {
        String base = trimSlash(cfg.endpoint);

        JSONObject ses = http("POST", base + "/session", cfg.apiKey, cfg.session,
                new JSONObject().put("title", "pocket-harness").toString());
        String id = ses.optString("id", ses.optString("ID", ""));
        if (id.isEmpty())
            throw new Exception("opencode: no session id in /session reply");

        String pm[] = splitModel(cfg.model);
        JSONObject body = new JSONObject()
                .put("providerID", pm[0])
                .put("modelID", pm[1])
                .put("parts", new JSONArray().put(
                        new JSONObject().put("type", "text").put("text", prompt)));

        JSONObject msg = http("POST", base + "/session/" + id + "/message",
                cfg.apiKey, cfg.session, body.toString());
        JSONArray parts = msg.optJSONArray("parts");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p != null && "text".equals(p.optString("type"))) {
                    String t = p.optString("text", "").trim();
                    if (!t.isEmpty()) return t;
                }
            }
        }
        // some builds answer {info:{parts:[...]}, parts:[...]}
        JSONObject info = msg.optJSONObject("info");
        if (info != null) {
            JSONArray ip = info.optJSONArray("parts");
            if (ip != null && ip.length() > 0) {
                JSONObject p0 = ip.optJSONObject(0);
                if (p0 != null) return p0.optString("text", "").trim();
            }
        }
        throw new Exception("opencode: no text part in reply");
    }

    /** generic OpenAI-compatible — Go-aware: picks /chat/completions, /responses, /messages per model */
    private static String viaOpenAI(Cfg cfg, String prompt) throws Exception {
        String shortModel = cfg.model.contains("/") ? cfg.shortModel() : cfg.model;
        String lower = shortModel.toLowerCase(java.util.Locale.US);
        String base = trimSlash(cfg.endpoint);
        boolean isGo = base.contains("/zen/go") || base.contains("/go/");
        // determine endpoint suffix per Go docs table
        String suffix;
        String bodyStr;
        if (base.endsWith("/chat/completions") || base.endsWith("/responses") || base.endsWith("/messages")) {
            // full URL pasted — use as-is, keep existing body shape
            suffix = "";
            bodyStr = new JSONObject()
                    .put("model", shortModel)
                    .put("temperature", 0)
                    .put("messages", new JSONArray().put(
                            new JSONObject().put("role", "user").put("content", prompt))).toString();
            String url = base;
            JSONObject resp = http("POST", url, cfg.apiKey, cfg.session, bodyStr);
            return parseOpenAIResponse(resp, url);
        }
        // Go routing: minimax/qwen -> anthropic /messages, grok/luna/muse-spark -> /responses, rest -> /chat/completions
        // per https://opencode.ai/docs/go#endpoints + https://opencode.ai/docs/zen#endpoints
        // deepseek/minimax/kimi/glm/hy/mimo/longcat -> /chat/completions ; qwen/minimax -> /messages ; grok/gpt/muse-spark -> /responses
        if (isGo && (lower.startsWith("minimax") || lower.startsWith("qwen3.8") || lower.startsWith("qwen3.7") || lower.startsWith("qwen3.6") || lower.startsWith("qwen3.5"))) {
            String url = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
            JSONObject body = new JSONObject()
                    .put("model", shortModel)
                    .put("max_tokens", 1024)
                    .put("messages", new JSONArray().put(
                            new JSONObject().put("role", "user").put("content", prompt)));
            JSONObject resp = http("POST", url, cfg.apiKey, cfg.session, body.toString());
            // anthropic shape: content[0].text or choices[0].message.content
            JSONArray content = resp.optJSONArray("content");
            if (content != null && content.length() > 0) {
                JSONObject c0 = content.optJSONObject(0);
                if (c0 != null) {
                    String t = c0.optString("text", "").trim();
                    if (!t.isEmpty()) return t;
                }
            }
            return parseOpenAIResponse(resp, url);
        } else if (isGo && (lower.contains("grok") || lower.contains("luna") || lower.contains("muse-spark"))) {
            String url = base.endsWith("/v1") ? base + "/responses" : base + "/v1/responses";
            JSONObject body = new JSONObject()
                    .put("model", shortModel)
                    .put("input", prompt);
            JSONObject resp = http("POST", url, cfg.apiKey, cfg.session, body.toString());
            // responses shape: output[0].content[0].text  or output_text
            String outText = resp.optString("output_text", "").trim();
            if (!outText.isEmpty()) return outText;
            JSONArray out = resp.optJSONArray("output");
            if (out != null && out.length() > 0) {
                JSONObject o0 = out.optJSONObject(0);
                if (o0 != null) {
                    JSONArray cont = o0.optJSONArray("content");
                    if (cont != null && cont.length() > 0) {
                        String t = cont.optJSONObject(0).optString("text", "").trim();
                        if (!t.isEmpty()) return t;
                    }
                }
            }
            return parseOpenAIResponse(resp, url);
        } else {
            String url;
            if (base.endsWith("/v1")) url = base + "/chat/completions";
            else url = base + "/v1/chat/completions";
            JSONObject body = new JSONObject()
                    .put("model", shortModel)
                    .put("temperature", 0)
                    .put("messages", new JSONArray().put(
                            new JSONObject().put("role", "user").put("content", prompt)));
            JSONObject resp = http("POST", url, cfg.apiKey, cfg.session, body.toString());
            return parseOpenAIResponse(resp, url);
        }
    }

    private static String parseOpenAIResponse(JSONObject resp, String url) throws Exception {
        // 1) OpenAI chat: choices[0].message.content
        JSONArray choices = resp.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject c0 = choices.getJSONObject(0);
            JSONObject msg = c0.optJSONObject("message");
            if (msg != null) {
                String t = msg.optString("content", "").trim();
                if (!t.isEmpty()) return t;
                // reasoning models sometimes return only reasoning_content
                String r = msg.optString("reasoning_content", msg.optString("reasoning", "")).trim();
                if (!r.isEmpty()) return r;
            }
            String t = c0.optString("text", "").trim();
            if (!t.isEmpty()) return t;
            JSONObject delta = c0.optJSONObject("delta");
            if (delta != null) {
                String td = delta.optString("content", "").trim();
                if (!td.isEmpty()) return td;
            }
        }
        // 2) Go responses API: output_text or output[0].content[0].text
        String outText = resp.optString("output_text", "").trim();
        if (!outText.isEmpty()) return outText;
        JSONArray out = resp.optJSONArray("output");
        if (out != null && out.length() > 0) {
            for (int i = 0; i < out.length(); i++) {
                JSONObject o = out.optJSONObject(i);
                if (o == null) continue;
                JSONArray cont = o.optJSONArray("content");
                if (cont != null) for (int j = 0; j < cont.length(); j++) {
                    JSONObject cj = cont.optJSONObject(j);
                    if (cj != null) {
                        String t = cj.optString("text", cj.optString("output_text", "")).trim();
                        if (!t.isEmpty()) return t;
                    }
                }
                String t = o.optString("text", "").trim();
                if (!t.isEmpty()) return t;
            }
        }
        // 3) Anthropic Go messages: content[0].text
        JSONArray content = resp.optJSONArray("content");
        if (content != null && content.length() > 0) {
            JSONObject c0 = content.optJSONObject(0);
            if (c0 != null) {
                String t = c0.optString("text", "").trim();
                if (!t.isEmpty()) return t;
            }
            // sometimes content is array of strings
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                Object v = content.opt(i);
                if (v instanceof JSONObject) sb.append(((JSONObject) v).optString("text", ""));
                else sb.append(String.valueOf(v));
            }
            String all = sb.toString().trim();
            if (!all.isEmpty()) return all;
        }
        // 4) direct fields
        for (String k : new String[]{"text", "response", "result", "message"}) {
            String t = resp.optString(k, "").trim();
            if (!t.isEmpty()) return t;
        }
        // 5) last resort: find any string containing a JSON array "[" 
        String raw = resp.toString();
        int lb = raw.indexOf('[');
        int rb = raw.lastIndexOf(']');
        if (lb >= 0 && rb > lb) {
            String cand = raw.substring(lb, rb + 1).trim();
            if (cand.length() > 2) return cand;
        }
        throw new Exception("openai: unexpected reply shape from " + url + snippet(raw));
    }

    // ---- vision ----
    private static String viaOpenAIVision(Cfg cfg, String prompt, String base64Jpeg) throws Exception {
        String shortModel = cfg.model.contains("/") ? cfg.shortModel() : cfg.model;
        String base = trimSlash(cfg.endpoint);
        // normalize to a chat/messages base — vision always uses OpenAI message array with image_url
        String url;
        if (base.endsWith("/chat/completions") || base.endsWith("/responses") || base.endsWith("/messages")) {
            url = base;
        } else if (base.endsWith("/v1")) {
            // Go vision models like deepseek-v4-flash-vision-exp are openai-compatible chat, not anthropic
            url = base + "/chat/completions";
        } else {
            url = base + "/v1/chat/completions";
        }
        // OpenAI vision shape: messages[0].content = [{type:text, text:prompt}, {type:image_url, image_url:{url:data:image/jpeg;base64,...}}]
        // opencode Go also accepts this for vision-exp models
        JSONObject imagePart = new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + base64Jpeg));
        JSONArray contentArr = new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", prompt))
                .put(imagePart);
        JSONObject body = new JSONObject()
                .put("model", shortModel)
                .put("temperature", 0)
                .put("max_tokens", 4096)
                .put("messages", new JSONArray().put(
                        new JSONObject().put("role", "user").put("content", contentArr)));
        android.util.Log.i("PH_AI", "vision POST " + url + " img " + base64Jpeg.length());
        JSONObject resp = http("POST", url, cfg.apiKey, cfg.session, body.toString());
        return parseOpenAIResponse(resp, url);
    }

    // ------------------------------------------------------------------- http

    /** opencode Go requires a stable session id so it can route + cache prompts. */
    private static void applySession(HttpURLConnection conn, String session) {
        if (session != null && !session.isEmpty())
            conn.setRequestProperty("x-opencode-session", session);
    }

    private static JSONObject http(String method, String url, String key, String session, String jsonBody)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TO);
            conn.setReadTimeout(READ_TO);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            applySession(conn, session);
            if (key != null && !key.isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + key);
            if (jsonBody != null) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = is == null ? "" : slurp(is);
            if (code >= 400)
                throw new Exception("HTTP " + code + snippet(text));
            if (text.isEmpty()) return new JSONObject();
            try { return new JSONObject(text); }
            catch (Exception e) { throw new Exception("non-JSON reply" + snippet(text)); }
        } finally {
            conn.disconnect();
        }
    }

    private static String slurp(InputStream is) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------------- helpers

    private static String[] splitModel(String m) {
        int i = m.indexOf('/');
        if (i <= 0 || i == m.length() - 1) return new String[]{"", m};
        return new String[]{m.substring(0, i), m.substring(i + 1)};
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String snippet(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "" : ": " + (s.length() > 90 ? s.substring(0, 90) + "…" : s);
    }

    /** translate raw exceptions into human hints */
    private static String friendly(Exception e) {
        String cls = String.valueOf(e);
        if (cls.contains("UnknownHost"))          return "DNS lookup failed · check URL + internet";
        if (cls.contains("ConnectException"))     return "connection refused · is the server up?";
        if (cls.contains("SocketTimeout"))        return "timed out · wrong port or slow server";
        if (cls.contains("SSL"))                  return "TLS failed · endpoint must be https://";
        String m = clean(e.getMessage());
        if (m.startsWith("HTTP 401") || m.startsWith("HTTP 403"))
            return m + " · missing or invalid API key";
        return m;
    }

    private static String clean(String m) {
        if (m == null || m.trim().isEmpty()) return "planner unreachable";
        return m.replaceAll("\\s+", " ").trim();
    }
}
