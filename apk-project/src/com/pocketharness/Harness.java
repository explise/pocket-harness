package com.pocketharness;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The brain. Offline rule-based NLU -> on-device skills.
 * No adb. No server. No internet needed for the agent itself.
 *
 * Grammar prototype (one clause per action, joined by "and"/"then"):
 *   open/launch <app>            · fuzzy-matched against installed apps
 *   play <thing> [on youtube|spotify|ytmusic]
 *   volume up/down/mute · set volume to N%
 *   brightness N% · max/min brightness
 *   torch on/off · flashlight
 *   call <number> · text <number> [saying <body>]
 *   navigate to <place> · search for <q> · google <q>
 *   camera · battery · time · date · wifi settings · bluetooth settings
 *   share <text> · screenshot(v2) · help
 */
public class Harness {

    private final Context ctx;
    private final PackageManager pm;
    private final AudioManager am;
    private static boolean torchOn = false;

    public Harness(Context ctx) {
        this.ctx = ctx;
        this.pm = ctx.getPackageManager();
        this.am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    // ------------------------------------------------------------------ entry

    public List<String> execute(String raw) {
        List<String> out = new ArrayList<>();
        for (String clause : clauses(raw)) {
            if (!clause.trim().isEmpty()) out.addAll(executeOne(clause));
        }
        return out;
    }

    /** split a raw command into action clauses ("and" / "then" chains) */
    public String[] clauses(String raw) {
        return raw.trim().split("\\s+(?:and|then)\\s+");
    }

    /** run exactly one clause; never returns null */
    public List<String> executeOne(String clause) {
        List<String> r = route(clause.trim());
        if (r != null) return r;
        List<String> o = new ArrayList<>();
        o.add("? didn't understand \"" + clause.trim() + "\"");
        o.add("  try: " + suggest());
        return o;
    }

    private String suggest() {
        return "\"open youtube\", \"play tu hi to hai\", \"torch on\", \"battery\", \"help\"";
    }

    // ----------------------------------------------------------------- router

    private List<String> route(String c) {
        String l = c.toLowerCase(Locale.US).trim();
        if (l.equals("help") || l.contains("what can you do")) return help();
        if (l.matches(".*(what('| i)?s the )?(time|date).*") && l.length() < 24) return now();

        java.util.regex.Matcher m;
        if ((m = rx("^(?:open|launch|start)\\s+(.+)$", l)) != null) return openApp(m.group(1));
        if ((m = rx("^play\\s+(.+)$", l)) != null) return playMedia(m.group(1));
        if (l.matches(".*volume.*") || l.matches(".*(louder|quieter|mute|unmute|silence).*"))
            return volume(l);
        if (l.contains("brightness")) return brightness(l);
        if (l.contains("torch") || l.contains("flashlight")) return torch(l);
        if ((m = rx("^(?:call|dial)\\s+(.+)$", l)) != null) return call(m.group(1));
        if ((m = rx("^text\\s+(.+)$", l)) != null) return sms(c.substring(4).trim());
        if ((m = rx("^(?:navigate to|directions to|take me to)\\s+(.+)$", l)) != null)
            return nav(m.group(1));
        if ((m = rx("^(?:search for|google|look up)\\s+(.+)$", l)) != null)
            return webSearch(m.group(1));
        if (l.matches(".*(camera|take a (photo|pic|picture)|selfie).*")) return camera();
        if (l.contains("battery")) return battery();
        if (l.contains("wifi") || l.contains("wi-fi") || l.contains("internet")) return panelNet();
        if (l.contains("bluetooth")) return panelBt();
        if (l.startsWith("share ")) return share(c.substring(6).trim());
        if (l.contains("screenshot")) {
            return screenshot();
        }
        // vision actions from LLM seeing page image
        if ((m = rx("^tap\\s+(\\d{1,4})\\s+(\\d{1,4})$", l)) != null) return tapXY(m.group(1), m.group(2));
        if ((m = rx("^tap\\s+(.+)$", l)) != null) return tapNamed(m.group(1));
        if ((m = rx("^(?:type|input)\\s+(.+)$", l)) != null) return inputText(m.group(1));
        if (l.equals("swipe up") || l.equals("scroll up")) return swipe(500, 750, 500, 250);
        if (l.equals("swipe down") || l.equals("scroll down")) return swipe(500, 250, 500, 750);
        if (l.equals("press enter") || l.equals("enter")) return enter();
        if (l.equals("back") || l.equals("go back") || l.equals("press back"))
            return item(HarnessService.globalBack() ? "▸ back" : "✗ back failed — enable Accessibility");
        if (l.equals("home") || l.equals("go home"))
            return item(HarnessService.globalHome() ? "▸ home" : "✗ home failed — enable Accessibility");
        if (l.equals("wait") || l.startsWith("wait ")) {
            try { Thread.sleep(900); } catch (Exception ignored) {}
            return item("▸ waited");
        }
        // bare app name, e.g. typing just "youtube"
        if (findBestApp(l) != null) return openApp(l);
        return null;
    }

    private static java.util.regex.Matcher rx(String re, String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(s);
        return m.find() ? m : null;
    }

    private List<String> item(String s) {
        List<String> o = new ArrayList<>();
        o.add(s);
        return o;
    }

    // ------------------------------------------------------------------ skills

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        ALIASES.put("yt", "youtube");
        ALIASES.put("you tube", "youtube");
        ALIASES.put("insta", "instagram");
        ALIASES.put("wa", "whatsapp");
        ALIASES.put("tg", "telegram");
        ALIASES.put("fb", "facebook");
        ALIASES.put("gmaps", "maps");
        ALIASES.put("google maps", "maps");
        ALIASES.put("chrome", "chrome");
    }

    private List<String> openApp(String nameRaw) {
        String name = expandAliases(nameRaw.toLowerCase(Locale.US).trim());
        String[] best = findBestApp(name);          // {label, packageName}
        if (best == null) {
            return item("✗ no installed app looks like \"" + nameRaw + "\"");
        }
        Intent i = pm.getLaunchIntentForPackage(best[1]);
        if (i == null) return item("✗ can't launch " + best[1]);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return item("▸ opened " + best[0] + "  (" + best[1] + ")");
    }

    private String expandAliases(String s) {
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            if (s.equals(e.getKey())) return e.getValue();
        }
        return s;
    }

    /** returns {label, pkg} of best-matching launchable app or null */
    private String[] findBestApp(String q) {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        String[] best = null;
        int bestScore = 0;
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.US).trim();
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.US);
            int s = score(q, label, pkg);
            if (s > bestScore) {
                bestScore = s;
                best = new String[]{String.valueOf(ri.loadLabel(pm)),
                        ri.activityInfo.packageName};
            }
        }
        return bestScore >= 40 ? best : null;
    }

    private int score(String q, String label, String pkg) {
        int s = 0;
        if (label.equals(q)) s = 100;
        else if (label.startsWith(q)) s = 88;
        else if (q.startsWith(label)) s = 82;
        else if (label.contains(q)) s = 72;
        else if (pkg.contains(q.replace(" ", ""))) s = 55;
        else {
            int d = lev(q, label);
            if (d <= Math.max(1, q.length() / 3)) s = 64 - d * 4;
        }
        return s;
    }

    private int lev(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++)
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
        return dp[a.length()][b.length()];
    }

    // ---- media ---------------------------------------------------------------

    private List<String> playMedia(String rest) {
        String service = "youtube";
        String q = rest.trim();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(.+?)\\s+on\\s+(youtube|ytmusic|spotify)$")
                        .matcher(q);
        if (m.find()) {
            q = m.group(1).trim();
            service = m.group(2);
        }
        q = stripFillers(q);

        if (service.equals("spotify")) {
            return view(Uri.parse("spotify:search:" + enc(q)), "Spotify search");
        }
        String pkg = service.equals("ytmusic")
                ? "com.google.android.apps.youtube.music"
                : "com.google.android.youtube";

        // Preferred: the same voice-search contract the Assistant uses.
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        i.setPackage(pkg);
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        i.putExtra("query", q);
        try {
            ctx.startActivity(i);
            return item("▸ asked " + (service.equals("ytmusic") ? "YT Music" : "YouTube")
                    + " to play \"" + q + "\"");
        } catch (Exception e) {
            // Fallback: deep-link straight to search results in the app/web.
            String url = service.equals("ytmusic")
                    ? "https://music.youtube.com/search?q=" + enc(q)
                    : "https://www.youtube.com/results?search_query=" + enc(q);
            return view(Uri.parse(url), (service.equals("ytmusic") ? "YT Music" : "YouTube")
                    + " search results");
        }
    }

    private String stripFillers(String q) {
        return q.replaceFirst("^(some|a|the)\\s+", "")
                .replaceFirst("\\s+(song|video|gaana|track)$", "");
    }

    // ---- audio / display -----------------------------------------------------

    private List<String> volume(String l) {
        if (l.contains("set volume") || l.contains("volume to") || l.matches(".*\\d+%?.*")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{1,3})").matcher(l);
            int pct = m.find() ? Integer.parseInt(m.group(1)) : 50;
            pct = Math.max(0, Math.min(100, pct));
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.round(max * pct / 100f), AudioManager.FLAG_SHOW_UI);
            return item("▸ media volume → " + pct + "%");
        }
        if (l.contains("up") || l.contains("loud")) {
            bump(AudioManager.ADJUST_RAISE);
            return item("▸ volume up");
        }
        if (l.contains("down") || l.contains("quiet")) {
            bump(AudioManager.ADJUST_LOWER);
            return item("▸ volume down");
        }
        if (l.contains("unmute")) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
            return item("▸ unmuted");
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
        return item("▸ muted");
    }

    private void bump(int dir) {
        for (int k = 0; k < 3; k++) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir,
                    AudioManager.FLAG_SHOW_UI);
        }
    }

    private List<String> brightness(String l) {
        if (!Settings.System.canWrite(ctx)) {
            requestWriteSettings();
            return item("! grant \"Modify system settings\" once, then ask again");
        }
        int val;
        if (l.contains("max")) val = 255;
        else if (l.contains("min") || l.contains("zero")) val = 25;
        else {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{1,3})").matcher(l);
            int pct = m.find() ? Integer.parseInt(m.group(1)) : 50;
            pct = Math.max(0, Math.min(100, pct));
            val = Math.max(25, Math.round(pct * 255f / 100));
        }
        Settings.System.putInt(ctx.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, val);
        return item("▸ brightness → " + Math.round(val * 100 / 255f) + "%");
    }

    private void requestWriteSettings() {
        Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    // ---- torch -----------------------------------------------------------

    private List<String> torch(String l) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String cid : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(cid)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    id = cid;
                    break;
                }
            }
            if (id == null) return item("✗ no flash unit found");
            boolean want;
            if (l.contains("off")) want = false;          // "torch off"
            else if (l.contains("on")) want = true;       // "torch on" — force on, not toggle
            else want = !torchOn;                          // "torch" alone = toggle
            cm.setTorchMode(id, want);
            torchOn = want;
            return item("▸ torch " + (want ? "ON" : "OFF"));
        } catch (Exception e) {
            return item("✗ torch failed: " + e.getMessage());
        }
    }

    // ---- comms -----------------------------------------------------------

    private List<String> call(String who) {
        String digits = who.replaceAll("[^0-9+]", "");
        Uri uri = digits.length() >= 3 ? Uri.parse("tel:" + digits)
                : Uri.parse("tel:");
        Intent i = new Intent(Intent.ACTION_DIAL, uri);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return item(digits.length() >= 3
                ? "▸ dialer ready for " + digits
                : "▸ opened dialer (contact lookup arrives in v2)");
    }

    private List<String> sms(String rest) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?)\\s*(?:saying|that says|msg|message)\\s+(.+)$")
                .matcher(rest);
        boolean matched = m.find();
        String body = matched ? m.group(2) : null;
        String to = matched ? m.group(1).trim() : rest;
        Uri uri = Uri.parse("smsto:" + (to.matches(".*\\d{3}.*") ? to : ""));
        Intent i = new Intent(Intent.ACTION_SENDTO, uri);
        if (body != null) i.putExtra("sms_body", body);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return item(body != null
                ? "▸ SMS app ready (to " + to.trim() + ", draft filled)"
                : "▸ SMS app ready");
    }

    // ---- misc -------------------------------------------------------------

    private List<String> nav(String place) {
        return view(Uri.parse("geo:0,0?q=" + enc(place)), "Maps navigation → " + place);
    }

    private List<String> webSearch(String q) {
        // Prefer an explicit browser URL (Chrome) so the search actually lands on a page.
        Uri search = Uri.parse("https://www.google.com/search?q=" + enc(q));
        Intent chrome = new Intent(Intent.ACTION_VIEW, search).setPackage("com.android.chrome");
        chrome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(chrome);
            return item("▸ searched Chrome for \"" + q + "\"");
        } catch (Exception ignored) { }
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, search)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return item("▸ searched the web for \"" + q + "\"");
        } catch (Exception ignored) { }
        // last resort: system search intent (may be Google app)
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
        i.putExtra(SearchManager.QUERY, q);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return item("▸ searched the web for \"" + q + "\"");
        } catch (Exception e) {
            return item("✗ no browser to search \"" + q + "\"");
        }
    }

    private List<String> camera() {
        return start(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "▸ camera open");
    }

    private List<String> battery() {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        Intent bs = ctx.registerReceiver(null,
                new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = bs == null ? 0 : bs.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int tempD = bs == null ? 0 : bs.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        String src = plugged == 0 ? "on battery"
                : plugged == BatteryManager.BATTERY_PLUGGED_AC ? "charging (AC)"
                : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "charging (USB)"
                : "charging (wireless)";
        List<String> o = new ArrayList<>();
        o.add("▸ battery " + pct + "%, " + src
                + ", " + (tempD / 10.0) + "°C");
        return o;
    }

    private List<String> now() {
        String t = new SimpleDateFormat("h:mm a · EEEE, d MMM yyyy", Locale.US)
                .format(new Date());
        return item("▸ " + t);
    }

    private List<String> panelNet() {
        return start(Build.VERSION.SDK_INT >= 29
                        ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                        : new Intent(Settings.ACTION_WIFI_SETTINGS),
                "▸ internet settings");
    }

    private List<String> panelBt() {
        // Settings.Panel has no public bluetooth action in the API-34 SDK,
        // so go straight to the classic bluetooth settings screen.
        return start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                "▸ bluetooth settings");
    }

    private List<String> share(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        ctx.startActivity(Intent.createChooser(i, "Share via"));
        return item("▸ share sheet open");
    }

    private List<String> help() {
        List<String> o = new ArrayList<>();
        o.add("verbs I know today:");
        o.add("  open/launch <app>      fuzzy-matches every installed app");
        o.add("  play <song> [on spotify]");
        o.add("  volume up/down/mute · set volume to N%");
        o.add("  brightness N% · max/min brightness");
        o.add("  torch on/off           camera flash");
        o.add("  call <number> · text <number> saying <msg>");
        o.add("  navigate to <place> · search for <query>");
        o.add("  camera · battery · time · wifi/bluetooth settings");
        o.add("  share <text>");
        o.add("chain them: open youtube and play tu hi to hai");
        return o;
    }

    // ---- vision helpers (via HarnessService) ----
    private List<String> screenshot() {
        if (!HarnessService.isEnabled()) return item("! enable Accessibility → Pocket Harness for screenshots");
        final String[] out = {null};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        HarnessService.screenshotBase64(new HarnessService.ShotCb() {
            public void onShot(String b64, int w, int h) { out[0] = "screenshot " + w + "x" + h + " " + b64.length() + "b"; latch.countDown(); }
            public void onError(String msg) { out[0] = "! " + msg; latch.countDown(); }
        });
        try { latch.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        return item(out[0] == null ? "! screenshot timeout" : "▸ " + out[0]);
    }

    private List<String> tapXY(String xs, String ys) {
        try {
            int x = Integer.parseInt(xs), y = Integer.parseInt(ys);
            // 0-1000 coord from LLM -> screen pixels
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int px = x <= 1000 ? Math.round(x * dm.widthPixels / 1000f) : x;
            int py = y <= 1000 ? Math.round(y * dm.heightPixels / 1000f) : y;
            boolean ok = HarnessService.tap(px, py);
            return item(ok ? "▸ tapped " + px + "," + py : "✗ tap failed — enable Accessibility");
        } catch (Exception e) { return item("✗ tap: " + e.getMessage()); }
    }

    private List<String> tapNamed(String where) {
        // for now treat named tap as search-bar heuristic: tap center top
        where = where.toLowerCase(java.util.Locale.US);
        if (where.contains("search")) return tapXY("500", "300");
        if (where.contains("address")) return tapXY("500", "150");
        return tapXY("500", "500");
    }

    private List<String> inputText(String txt) {
        boolean ok = HarnessService.inputText(txt);
        return item(ok ? "▸ typed \"" + txt + "\"" : "✗ input failed");
    }

    private List<String> swipe(int x1, int y1, int x2, int y2) {
        if (!HarnessService.isEnabled()) return item("! enable Accessibility → Pocket Harness for swipes");
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int px1 = Math.round(x1 * dm.widthPixels / 1000f);
        int py1 = Math.round(y1 * dm.heightPixels / 1000f);
        int px2 = Math.round(x2 * dm.widthPixels / 1000f);
        int py2 = Math.round(y2 * dm.heightPixels / 1000f);
        boolean ok = HarnessService.swipe(px1, py1, px2, py2, 300);
        return item(ok ? "▸ swiped " + x1 + "," + y1 + " → " + x2 + "," + y2
                : "✗ swipe failed — enable Accessibility");
    }

    private List<String> enter() {
        if (HarnessService.imeEnter()) return item("▸ enter");
        return item("! enter needs a focused field + Accessibility (Android 11+)");
    }

    // ---- helpers -------------------------------------------------------------

    private List<String> start(Intent i, String okMsg) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return item(okMsg);
        } catch (Exception e) {
            return item("✗ " + okMsg.replaceFirst("^▸\\s*", "") + " failed: " + e.getMessage());
        }
    }

    private List<String> view(Uri uri, String label) {
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return item("▸ opened " + (label == null ? uri.toString() : label));
        } catch (Exception e) {
            return item("✗ nothing can open that (" + e.getMessage() + ")");
        }
    }

    private String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s.replace(" ", "+");
        }
    }
}
