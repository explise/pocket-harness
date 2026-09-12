package com.pocketharness;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pocket Harness v0.2 — "Technical Premium" UI.
 * OLED canvas · tonal zinc layers · 1px borders · indigo = action · cyan = streaming.
 * Single activity, programmatic views, zero external dependencies.
 */
public class MainActivity extends Activity {

    // ---- design tokens — refined dark ("technical premium") --------------
    // radius scale: R_SM inputs/chips-base 10 · R_MD cards 14 · R_LG composer/sheets 20 · pill 999
    static final int R_SM = 10, R_MD = 14, R_LG = 20;
    static final int CANVAS    = 0xFF08090B; // window / status bar
    static final int SURFACE   = 0xFF0B0C0F; // app background
    static final int CARD      = 0xFF141519; // raised cards + composer
    static final int INSET     = 0xFF0A0B0D; // inputs (recessed)
    static final int NAV_BG    = 0xFF0A0A0C;
    static final int SC_LO     = 0xFF141519; // surfaces: chips, cards, sheets
    static final int SC_LOW    = 0xFF17181D; // lifted surface
    static final int SC_HIGH   = 0xFF2A2C33; // grab handle / strong surface
    static final int BORDER    = 0xFF23252B; // hairline
    static final int BORDER_LT = 0xFF363943; // focus / hover
    static final int GLOW_INDIGO = 0x2E6D5BFF;
    static final int ON_SURF   = 0xFFEDEEF1; // primary text
    static final int MUTED     = 0xFFA7A9B4; // secondary text
    static final int OUTLINE   = 0xFF70737C; // tertiary text / labels
    static final int INDIGO    = 0xFF6D5BFF; // primary action
    static final int INDIGO_HI = 0xFF8B7CFF;
    static final int CYAN      = 0xFF2DD4EF; // success / streaming
    static final int AMBER     = 0xFFFFB783; // partial
    static final int RED       = 0xFFFF8A7A; // failure

    private static final int REQ_SPEECH = 42;
    private static final int REQ_MIC = 43;
    final int VERT = LinearLayout.VERTICAL, HORIZ = LinearLayout.HORIZONTAL;

    // ---- state ----------------------------------------------------------------
    SharedPreferences sp;
    Typeface fSans, fMono;
    Harness harness;
    FrameLayout content;
    LinearLayout navHolder;
    String[] navIds;
    EditText cmdInput;
    TextView aiTestStat;
    LinearLayout recentFeed;
    LinearLayout answersBox;
    ScrollView chatScroll;
    LinearLayout chatBox;
    boolean chatSavedForRun = false;
    String currentScreen = "home";

    // plan sheet refs
    FrameLayout backdrop;
    LinearLayout sheet, stepsBox, sheetStatBox;
    ValueAnimator chipPulse;
    int runSeq = 0; // guards auto-dismiss against newer runs / manual dismiss
    static final int VISION_MAX_STEPS = 16;
    boolean visionLoopActive = false; // observe→act agent running
    boolean readAttempted = false;    // research goal already tried a page read
    TextView planCmd, runMeta;

    // voice hold-to-talk refs
    FrameLayout voiceBackdrop;
    LinearLayout voiceSheet, voiceBars, voiceStatBox;
    TextView voiceTranscript, voiceHint;
    ValueAnimator voiceBarsAnim, voicePulse;
    SpeechRecognizer speechRecog;
    boolean voiceListening = false;
    int voiceBusyRetries = 0; // auto-retry a few times if the mic is momentarily busy
    String pendingVoiceAction = ""; // "start" when mic perm needed then auto-start

    // v0.4 wake word
    SpeechRecognizer wakeRecog;
    TextToSpeech tts;
    boolean wakeEnabled = false;
    boolean wakeListening = false;
    boolean wakePausedForVoice = false;
    View wakeDot; // tiny indicator on console header
    final Handler wakeHandler = new Handler(Looper.getMainLooper());
    Runnable wakeRestart;

    final Handler ui = new Handler(Looper.getMainLooper());
    // clause execution runs here so blocking skills (screenshot latch, package scans,
    // startActivity) never stall the UI thread / trigger an ANR
    final java.util.concurrent.ExecutorService runner =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(CANVAS);
        getWindow().setNavigationBarColor(CANVAS);
        sp = getPreferences(MODE_PRIVATE);
        fSans = loadFont("geist", Typeface.SANS_SERIF);
        fMono = loadFont("jbmono", Typeface.MONOSPACE);
        harness = new Harness(this);
        buildRoot();
        showScreen(sp.getBoolean("ob_done", false) ? "home" : "onboard");
        handleCmdIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        android.util.Log.i("PH_AI", "onNewIntent " + it.getStringExtra("cmd"));
        setIntent(it);
        handleCmdIntent(it);
    }

    void handleCmdIntent(Intent it) {
        android.util.Log.i("PH_AI", "handleCmdIntent " + (it == null ? "null" : it.getStringExtra("cmd")));
        if (it == null) return;
        java.util.Map<String, String> strX2 = new java.util.HashMap<>();
        for (String k : new String[]{"ai_ep", "ai_model", "ai_mode", "ai_key"}) {
            String v = it.getStringExtra(k);
            if (v != null && !v.trim().isEmpty()) strX2.put(k, v.trim());
        }
        if (!strX2.isEmpty()) {
            android.content.SharedPreferences.Editor ed = sp.edit();
            for (java.util.Map.Entry<String, String> e : strX2.entrySet()) ed.putString(e.getKey(), e.getValue());
            ed.apply();
        }
        if (it.hasExtra("ai_on")) sp.edit().putBoolean("ai_on", it.getBooleanExtra("ai_on", false)).apply();
        if (it.getBooleanExtra("ai_models", false)) {
            ui.postDelayed(() -> {
                showScreen("settings");
                ui.postDelayed(() -> showModelPicker(), 700);
            }, 500);
        }
        if (it.getBooleanExtra("ai_test", false)) {
            ui.postDelayed(() -> {
                showScreen("settings");
                ui.postDelayed(() -> runAiTest(), 700);
            }, 500);
        }
        String bootCmd = it.getStringExtra("cmd");
        if (bootCmd != null && !bootCmd.trim().isEmpty()) {
            final String bc = bootCmd.trim();
            ui.postDelayed(() -> {
                if (!"home".equals(currentScreen)) showScreen("home");
                if (cmdInput != null) {
                    cmdInput.clearFocus();
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(cmdInput.getWindowToken(), 0);
                    ui.postDelayed(() -> {
                        cmdInput.setText(bc);
                        runCommand();
                    }, 250);
                }
            }, 700);
        }
    }

    private Typeface loadFont(String name, Typeface fallback) {
        try {
            int id = getResources().getIdentifier(name, "font", getPackageName());
            if (id == 0) return fallback;
            // Resources.getFont(int) is API 26+; minSdk is 24.
            if (android.os.Build.VERSION.SDK_INT >= 26) return getResources().getFont(id);
            java.io.File f = new java.io.File(getCacheDir(), name + ".ttf");
            if (!f.exists()) {
                try (java.io.InputStream in = getResources().openRawResource(id);
                     java.io.OutputStream out = new java.io.FileOutputStream(f)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            return Typeface.createFromFile(f);
        } catch (Throwable e) { return fallback; }
    }

    // =========================================================== ui factories ==
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView tv(String t, float sizeSp, int color, boolean bold, boolean mono) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setTypeface(mono ? fMono : fSans, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    /** section label: readable, higher-contrast mono caps */
    TextView caps(String t) {
        TextView v = tv(t, 11, MUTED, true, true);
        v.setLetterSpacing(0.07f);
        v.setAllCaps(true);
        return v;
    }

    LinearLayout col(int orientation) { LinearLayout l = new LinearLayout(this); l.setOrientation(orientation); return l; }
    LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }
    void pad(View v, int l, int t, int r, int b) { v.setPadding(dp(l), dp(t), dp(r), dp(b)); }

    GradientDrawable box(int radiusDp, int fill, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp >= 999 ? dp(999) : dp(radiusDp));
        if (strokeColor != 0) g.setStroke(1, strokeColor);
        return g;
    }

    Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setTypeface(fSans, Typeface.BOLD);
        b.setLetterSpacing(0.01f);
        b.setPadding(dp(20), dp(12), dp(20), dp(12));
        b.setMinHeight(dp(46));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(999)); // pill — primary action language
        if (primary) {
            g.setColor(INDIGO);
            // subtle violet glow via elevation surrogate
            b.setElevation(dp(6));
        } else { g.setColor(Color.TRANSPARENT); g.setStroke(1, BORDER); }
        b.setBackground(g);
        b.setStateListAnimator(null);
        // press physics: 0.97 scale + 1dp translate, snap easing via ViewPropertyAnimator.
        // Consume the events (return true) so onTouchEvent doesn't also fire performClick —
        // otherwise every button click runs twice.
        b.setOnTouchListener((v, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.97f).scaleY(0.97f).translationZ(dp(1)).setDuration(90).start();
                if (primary) v.setBackground(box(999, INDIGO_HI, 0));
                return true;
            } else if (a == MotionEvent.ACTION_UP) {
                v.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
                if (primary) v.setBackground(box(999, INDIGO, 0));
                v.performClick();
                return true;
            } else if (a == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
                if (primary) v.setBackground(box(999, INDIGO, 0));
                return true;
            }
            return false;
        });
        return b;
    }

    TextView chip(String text) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextSize(11);
        c.setTextColor(MUTED);
        c.setTypeface(fMono, Typeface.NORMAL);
        c.setLetterSpacing(0.02f);
        c.setBackground(box(999, SC_LO, BORDER));
        pad(c, 14, 7, 14, 7); // was 12/6 — more premium
        c.setElevation(dp(1));
        c.setOnClickListener(v -> {
            if (cmdInput != null) { cmdInput.setText(text); cmdInput.setSelection(text.length()); }
            else { sp.edit().putString("pending_cmd", text).apply(); showScreen("home"); }
            c.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction(() ->
                    c.animate().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start()).start();
            c.setBackground(box(999, SC_LO, INDIGO));
            c.postDelayed(() -> c.setBackground(box(999, SC_LO, BORDER)), 180);
        });
        return c;
    }

    /** detailed-status component: 6px pulsing dot + mono caps label */
    TextView stat(String label, int dotColor, boolean pulse) {
        TextView s = tv(label, 10, OUTLINE, true, true);
        s.setLetterSpacing(0.06f);
        s.setAllCaps(true);
        s.setCompoundDrawablesWithIntrinsicBounds(mkDot(dotColor), null, null, null);
        s.setCompoundDrawablePadding(dp(8));
        s.setTag(pulse); // remember for potential restyle
        return s;
    }

    GradientDrawable mkDot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setSize(dp(6), dp(6));
        return d;
    }

    ImageView icon(int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        if (tint != 0) iv.setColorFilter(tint);
        iv.setLayoutParams(new ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    // ================================================================= skeleton ==
    // subtle gold atmosphere: radial glow + grain (felt not seen, opacity <0.05)
    View addAtmosphere(FrameLayout root) {
        View glow = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x0D6D5BFF, 0x00000000});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(420));
        g.setGradientCenter(0.35f, 0f);
        glow.setBackground(g);
        root.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280), Gravity.TOP));
        View hair = new View(this);
        hair.setBackgroundColor(0x08FFFFFF); // 3% white grain hint — ultra subtle
        hair.setAlpha(0.4f);
        root.addView(hair, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return glow;
    }

    void buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(SURFACE);
        addAtmosphere(root);

        content = new FrameLayout(this);
        // swallow initial focus so the console input doesn't pop the keyboard on launch
        root.setFocusableInTouchMode(true);
        root.setFocusable(true);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP));

        buildSheetOverlay(root);
        buildVoiceOverlay(root);

        LinearLayout nav = buildNav();
        root.addView(nav, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76), Gravity.BOTTOM));

        setContentView(root);
        // keep screens clear of the nav bar
        content.setPadding(0, 0, 0, dp(68));
    }

    LinearLayout buildNav() {
        LinearLayout wrapper = col(VERT);
        // frosted spec: rgba(0,0,0,.85) + blur(8px) from mockups.html:112
        // solid NAV_BG (0xFF0A0A0C) → translucent; RenderEffect blur on API 31+
        wrapper.setBackgroundColor(0xD9000000);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                wrapper.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        18f, 18f, android.graphics.Shader.TileMode.CLAMP));
            } catch (Exception ignored) { }
        }
        View border = new View(this);
        border.setBackgroundColor(BORDER);
        wrapper.addView(border, lp(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout n = col(HORIZ);

        navIds = new String[]{"home", "runs", "skills", "settings"};
        String[] lbls  = {"CONSOLE", "RUNS", "SKILLS", "SETTINGS"};
        int[]    icons = {R.drawable.ic_home, R.drawable.ic_runs, R.drawable.ic_grid, R.drawable.ic_sliders};

        for (int i = 0; i < navIds.length; i++) {
            final String id = navIds[i];
            LinearLayout item = col(VERT);
            item.setGravity(Gravity.CENTER);
            item.setOnClickListener(v -> showScreen(id));
            ImageView ic = icon(icons[i], OUTLINE, 22);
            ic.setTag("ic:" + id);
            TextView lb = tv(lbls[i], 10, OUTLINE, true, true);
            lb.setLetterSpacing(0.06f);
            lb.setTag("lb:" + id);
            LinearLayout.LayoutParams ilp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = dp(5);
            item.addView(ic);
            item.addView(lb, ilp);
            n.addView(item, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        wrapper.addView(n, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(67)));
        navHolder = wrapper;
        return wrapper;
    }

    void setNavActive(String id) {
        if (navIds == null) return;
        for (String s : navIds) {
            ImageView ic = navHolder.findViewWithTag("ic:" + s);
            TextView lb = navHolder.findViewWithTag("lb:" + s);
            boolean on = s.equals(id);
            if (ic != null) {
                ic.setColorFilter(on ? INDIGO_HI : OUTLINE);
                ic.animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).setDuration(220)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
            }
            if (lb != null) {
                lb.setTextColor(on ? INDIGO_HI : OUTLINE);
                lb.animate().alpha(on ? 1f : 0.85f).setDuration(180).start();
            }
        }
    }

    void showScreen(String id) {
        currentScreen = id;
        content.removeAllViews();
        View v;
        switch (id) {
            case "onboard":  v = screenOnboard(); break;
            case "runs":     v = screenRuns(); break;
            case "skills":   v = screenSkills(); break;
            case "settings": v = screenSettings(); break;
            default:         v = screenHome(); break;
        }
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setNavActive(id.equals("onboard") ? "" : id);
    }

    ScrollView scrollHost(View inner) {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setVerticalScrollBarEnabled(false);
        pad(sc, 16, 8, 16, 24);
        sc.addView(inner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    // =============================================================== onboarding ==
    View screenOnboard() {
        LinearLayout root = col(VERT);
        pad(root, 20, 44, 20, 20);

        root.addView(stat("harness core online", CYAN, true));

        TextView head = tv("Give the harness\nits hands.", 32, ON_SURF, true, false);
        head.setLetterSpacing(-0.02f);
        head.setLineSpacing(dp(2), 1f);
        pad(head, 0, 18, 0, 0);
        root.addView(head);

        TextView sub = tv("Pocket Harness works fully offline. Each capability below is granted natively — nothing leaves the device.",
                15, MUTED, false, false);
        pad(sub, 0, 10, 0, 0);
        root.addView(sub);

        TextView gcap = caps("native grants");
        pad(gcap, 0, 24, 0, 8);
        root.addView(gcap);

        root.addView(permCard("write", "Modify system settings", "brightness · volume levels", R.drawable.ic_sliders));
        root.addView(permCard("a11y",  "Accessibility service",  "taps · screenshots · global back/home", R.drawable.ic_grid));
        root.addView(permCard("mic",   "Microphone",             "voice commands via recognizer intent", R.drawable.ic_mic));

        LinearLayout row = col(HORIZ);
        pad(row, 0, 28, 0, 0);
        Button later = button("Later", false);
        later.setOnClickListener(v -> finishOnboarding());
        LinearLayout.LayoutParams llp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.rightMargin = dp(8);
        row.addView(later, llp);
        Button go = button("Start harnessing ⚡", true);
        go.setOnClickListener(v -> finishOnboarding());
        row.addView(go, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        root.addView(row);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        return sc;
    }

    View permCard(final String key, String name, String sub, int iconRes) {
        final LinearLayout c = col(HORIZ);
        c.setBackground(box(R_MD, SC_LO, BORDER));
        pad(c, 12, 11, 12, 11);
        c.setGravity(Gravity.CENTER_VERTICAL);

        c.addView(icon(iconRes, MUTED, 20));

        LinearLayout mid = col(VERT);
        mid.addView(tv(name, 14, ON_SURF, true, false));
        TextView s = tv(sub, 12, MUTED, false, false);
        pad(s, 0, 2, 0, 0);
        mid.addView(s);
        LinearLayout.LayoutParams mlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(12);
        c.addView(mid, mlp);

        if (sp.getBoolean("pg_" + key, false)) {
            c.addView(stat("granted", CYAN, false));
        } else {
            Button g = button("Grant", false);
            g.setOnClickListener(v -> {
                if ("mic".equals(key)) {
                    pendingVoiceAction = ""; // grant only; don't auto-start listening
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                } else if ("write".equals(key)) {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                } else if ("a11y".equals(key)) {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception ignored) { }
                } else {
                    sp.edit().putBoolean("pg_" + key, true).apply();
                    c.removeView(g);
                    c.addView(stat("granted", CYAN, false));
                }
            });
            c.addView(g);
        }
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(8);
        c.setLayoutParams(p);
        return c;
    }

    void finishOnboarding() {
        sp.edit().putBoolean("ob_done", true).apply();
        showScreen("home");
    }

    // ==================================================================== home ==
    View screenHome() {
        LinearLayout root = col(VERT);

        // ---- chat header: avatar + name + status + wake ---------------------
        LinearLayout head = col(HORIZ);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(CARD);
        pad(head, 16, 10, 16, 10);

        FrameLayout av = new FrameLayout(this);
        av.setBackground(box(999, 0xFF1B1740, BORDER));
        av.addView(icon(R.drawable.ic_robot, 0, 38),
                new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        head.addView(av, lp(dp(40), dp(40)));

        LinearLayout mid = col(VERT);
        pad(mid, 12, 0, 8, 0);
        mid.addView(tv("Pocket Harness", 17, ON_SURF, true, false));
        AIClient.Cfg hcfg = AIClient.load(sp);
        String hsub = hcfg.ready() ? "online · " + hcfg.shortModel()
                : skillCount() + " verbs · offline core";
        mid.addView(tv(hsub, 11, MUTED, false, true));
        head.addView(mid, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout wakePill = col(HORIZ);
        wakePill.setGravity(Gravity.CENTER_VERTICAL);
        wakePill.setBackground(box(999, SC_LO, BORDER));
        pad(wakePill, 10, 5, 12, 5);
        wakeDot = new View(this);
        wakeDot.setBackground(box(999, BORDER, 0));
        wakePill.addView(wakeDot, lp(dp(7), dp(7)));
        TextView wakeLbl = tv("wake", 10, MUTED, true, true);
        wakeLbl.setAllCaps(true);
        wakeLbl.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams wlp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = dp(7);
        wakePill.addView(wakeLbl, wlp);
        wakePill.setOnClickListener(v -> showScreen("settings"));
        head.addView(wakePill);
        root.addView(head);

        View hair = new View(this);
        hair.setBackgroundColor(BORDER);
        root.addView(hair, lp(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // ---- chat thread -----------------------------------------------------
        chatScroll = new ScrollView(this);
        chatScroll.setVerticalScrollBarEnabled(false);
        chatBox = col(VERT);
        pad(chatBox, 12, 12, 12, 12);
        refreshChat();
        chatScroll.addView(chatBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(chatScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- quick suggestions ----------------------------------------------
        HorizontalScrollView chipsHs = new HorizontalScrollView(this);
        chipsHs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = col(HORIZ);
        pad(chips, 12, 4, 12, 4);
        String[] examples = {"check messages", "go to wikipedia", "torch off", "battery"};
        for (String e : examples) {
            TextView ch = chip(e);
            LinearLayout.LayoutParams clp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(8);
            chips.addView(ch, clp);
        }
        chipsHs.addView(chips);
        root.addView(chipsHs, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- composer dock ----------------------------------------------------
        View hair2 = new View(this);
        hair2.setBackgroundColor(BORDER);
        root.addView(hair2, lp(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout dock = col(HORIZ);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackgroundColor(CARD);
        pad(dock, 10, 8, 10, 8);

        cmdInput = new EditText(this);
        cmdInput.setHint("message pocket harness…");
        cmdInput.setHintTextColor(OUTLINE);
        cmdInput.setTextColor(ON_SURF);
        cmdInput.setTextSize(15);
        cmdInput.setTypeface(fSans);
        cmdInput.setBackground(box(999, INSET, BORDER));
        cmdInput.setPadding(dp(18), dp(12), dp(18), dp(12));
        cmdInput.setSingleLine(true);
        cmdInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        cmdInput.setOnFocusChangeListener((v, has) ->
                v.setBackground(box(999, INSET, has ? INDIGO : BORDER)));
        cmdInput.setOnEditorActionListener((v, a, ev) -> { runCommand(); return true; });
        dock.addView(cmdInput, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout micBtn = new FrameLayout(this);
        micBtn.setBackground(box(999, SC_LOW, BORDER));
        micBtn.setClickable(true);
        micBtn.addView(icon(R.drawable.ic_mic, INDIGO_HI, 20),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        micBtn.setOnClickListener(v -> startSpeech());
        LinearLayout.LayoutParams micLp = lp(dp(44), dp(44));
        micLp.leftMargin = dp(8);
        dock.addView(micBtn, micLp);

        FrameLayout sendBtn = new FrameLayout(this);
        sendBtn.setBackground(box(999, INDIGO, 0));
        sendBtn.setClickable(true);
        sendBtn.setElevation(dp(1));
        sendBtn.addView(icon(R.drawable.ic_send, Color.WHITE, 20),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        sendBtn.setOnClickListener(v -> runCommand());
        LinearLayout.LayoutParams sendLp = lp(dp(46), dp(46));
        sendLp.leftMargin = dp(8);
        dock.addView(sendBtn, sendLp);
        root.addView(dock);

        // preload a command chosen on the Skills screen
        String pending = sp.getString("pending_cmd", "");
        if (!pending.isEmpty()) {
            sp.edit().remove("pending_cmd").apply();
            cmdInput.setText(pending);
            cmdInput.setSelection(pending.length());
        }
        return root;
    }

    int skillCount() { return SKILLS.length + 12; } // skills page + inline device verbs

    void refreshRecentFeed() {
        if (recentFeed == null) return;
        recentFeed.removeAllViews();
        JSONArray hist = loadHistory();
        int shown = 0;
        for (int i = hist.length() - 1; i >= 0 && shown < 6; i--, shown++) {
            try {
                JSONObject o = hist.getJSONObject(i);
                LinearLayout card = runCard(o.getLong("t"), o.getString("cmd"),
                        o.optString("res", ""), o.optString("st", "DONE"));
                card.setAlpha(0f); card.setTranslationY(dp(6));
                final int delay = Math.min(shown, 5) * 55;
                card.postDelayed(() -> card.animate().alpha(1f).translationY(0).setDuration(280)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start(), delay);
                recentFeed.addView(card);
            } catch (Exception ignored) { }
        }
        if (shown == 0) {
            LinearLayout empty = col(VERT);
            empty.setBackground(box(R_MD, SC_LO, BORDER));
            pad(empty, 16, 18, 16, 18);
            empty.addView(tv("No runs yet", 14, ON_SURF, true, false));
            TextView e2 = tv("Type a command above, or tap a suggestion.", 12, MUTED, false, false);
            pad(e2, 0, 4, 0, 0);
            empty.addView(e2);
            recentFeed.addView(empty);
        }
    }

    LinearLayout runCard(long ts, String cmd, String res, String status) {
        LinearLayout card = col(VERT);
        card.setBackground(box(R_MD, SC_LO, BORDER));
        pad(card, 14, 12, 14, 12);

        LinearLayout top = col(HORIZ);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = tv(timeStr(ts), 11, OUTLINE, true, true);
        t.setLetterSpacing(0.03f);
        top.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int dotCol; String stLabel;
        if ("NOMATCH".equals(status))      { dotCol = RED;   stLabel = "no match"; }
        else if ("PARTIAL".equals(status)) { dotCol = AMBER; stLabel = "partial"; }
        else                               { dotCol = CYAN;  stLabel = "done"; }
        TextView pill = stat(stLabel, dotCol, "DONE".equals(status));
        pill.setTextSize(9);
        top.addView(pill);
        card.addView(top);

        TextView c = tv(cmd, 15, ON_SURF, true, false);
        c.setLetterSpacing(-0.01f);
        c.setLineSpacing(dp(1), 1f);
        pad(c, 0, 7, 0, 0);
        card.addView(c);

        if (!res.isEmpty()) {
            TextView r = tv(res, 12, MUTED, false, false);
            r.setLineSpacing(dp(1), 1f);
            pad(r, 0, 3, 0, 0);
            card.addView(r);
        }
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(8);
        card.setLayoutParams(p);
        return card;
    }

    String timeStr(long ms) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND));
    }

    // ==================================================================== runs ==
    View screenRuns() {
        LinearLayout root = col(VERT);
        pad(root, 20, 12, 20, 0);
        root.addView(tv("Runs", 24, ON_SURF, true, false));

        LinearLayout body = col(VERT);
        pad(body, 0, 12, 0, 24);
        EditText q = new EditText(this);
        q.setHint("search history…");
        q.setHintTextColor(OUTLINE);
        q.setTextColor(ON_SURF);
        q.setTextSize(15);
        q.setTypeface(fSans);
        q.setBackground(box(R_SM, INSET, BORDER));
        q.setPadding(dp(16), dp(14), dp(16), dp(14));
        q.setSingleLine(true);
        body.addView(q, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout list = col(VERT);
        pad(list, 0, 12, 0, 0);
        body.addView(list, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable[] ref = new Runnable[1];
        ref[0] = () -> {
            String needle = q.getText().toString().toLowerCase(java.util.Locale.US).trim();
            list.removeAllViews();
            JSONArray hist = loadHistory();
            boolean empty = true;
            int shown = 0;
            for (int i = hist.length() - 1; i >= 0; i--) {
                try {
                    JSONObject o = hist.getJSONObject(i);
                    String cmd = o.getString("cmd");
                    String res = o.optString("res", "");
                    if (!needle.isEmpty() && !cmd.toLowerCase(java.util.Locale.US).contains(needle)
                            && !res.toLowerCase(java.util.Locale.US).contains(needle)) continue;
                    list.addView(runCard(o.getLong("t"), cmd, res, o.optString("st", "DONE")));
                    empty = false; shown++;
                } catch (Exception ignored) { }
            }
            if (empty) {
                String msg = needle.isEmpty() ? "nothing yet" : "no match for \"" + needle + "\"";
                list.addView(tv(msg, 13, OUTLINE, false, true));
            }
            if (!needle.isEmpty() && shown > 0) {
                TextView cnt = tv(shown + " match" + (shown > 1 ? "es" : ""), 10, OUTLINE, true, true);
                cnt.setLetterSpacing(0.06f); cnt.setAllCaps(true);
                pad(cnt, 0, 0, 0, 8);
                list.addView(cnt, 0);
            }
        };
        q.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { ref[0].run(); }
        });
        ref[0].run();

        root.addView(scrollHost(body), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    TextView sectionGap(String label) {
        TextView g = caps(label.toLowerCase());
        pad(g, 0, 24, 0, 8);
        return g;
    }

    // ================================================================== skills ==
    static final String[][] SKILLS = {
        {"Apps",   "Launch app",     "\"open insta\"",                        ""},
        {"Media",  "Play media",     "\"play take five on spotify\"",         "YT·YTM·SPOTIFY"},
        {"Media",  "Volume",         "\"set volume to 30%\"",                 ""},
        {"Device", "Flashlight",     "\"torch off\"",                         ""},
        {"Device", "Brightness",     "\"max brightness\"",                    ""},
        {"Device", "Battery status", "\"battery\"",                           ""},
        {"Device", "Screenshot",     "via accessibility service",             "v0.2"},
        {"Comms",  "Call",           "\"call 98765…\"",                       ""},
        {"Comms",  "Text",           "\"text dad saying omw\"",               ""},
        {"Web",    "Navigate",       "\"navigate to kempegowda airport\"",    ""},
        {"Web",    "Search",         "\"google best filter coffee\"",         ""},
    };

    View screenSkills() {
        LinearLayout root = col(VERT);
        pad(root, 20, 12, 20, 0);
        root.addView(tv("Skills", 24, ON_SURF, true, false));
        TextView st = tv("Tap a skill to preload it in the console", 13, MUTED, false, false);
        pad(st, 0, 6, 0, 0);
        root.addView(st);

        LinearLayout list = col(VERT);
        pad(list, 0, 4, 0, 24);
        String lastGroup = null;
        for (String[] s : SKILLS) {
            if (!s[0].equals(lastGroup)) { lastGroup = s[0]; list.addView(sectionGap(s[0])); }
            LinearLayout row = col(HORIZ);
            row.setBackground(box(R_MD, SC_LO, BORDER)); // was 8 — gold md
            row.setElevation(dp(1));
            pad(row, 16, 14, 16, 14); // was 12/11 — breathe
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout mid = col(VERT);
            mid.addView(tv(s[1], 15, ON_SURF, false, false));
            TextView ex = tv(s[2], 11, OUTLINE, false, true);
            pad(ex, 0, 2, 0, 0);
            mid.addView(ex);
            row.addView(mid, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (!s[3].isEmpty()) {
                TextView tag = tv(s[3], 10, AMBER, true, true);
                tag.setBackground(box(999, Color.TRANSPARENT, BORDER));
                pad(tag, 8, 2, 8, 2);
                row.addView(tag);
            }
            final String example = s[2].replace("\"", "").trim();
            row.setOnClickListener(v -> {
                sp.edit().putString("pending_cmd", example).apply();
                showScreen("home");
                ui.postDelayed(() -> {
                    if (cmdInput != null) {
                        cmdInput.setText(example);
                        cmdInput.setSelection(example.length());
                        cmdInput.requestFocus();
                    }
                }, 80);
            });
            LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.bottomMargin = dp(8);
            row.setLayoutParams(p);
            list.addView(row);
        }
        root.addView(scrollHost(list), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ================================================================ settings ==
    View screenSettings() {
        LinearLayout root = col(VERT);
        pad(root, 20, 12, 20, 0);
        root.addView(tv("Settings", 24, ON_SURF, true, false));

        LinearLayout body = col(VERT);
        pad(body, 0, 8, 0, 24);

        body.addView(sectionGap("behavior"));
        LinearLayout behav = col(VERT);
        behav.setBackground(box(R_MD, SC_LO, BORDER));
        behav.setElevation(dp(1));
        pad(behav, 14, 6, 14, 6);
        settingRow(behav, "Haptic confirm", "vibrate on step completion", "set_haptic", true, true, null);
        settingRow(behav, "Spoken replies", "TTS confirms each step",       "set_tts", false, true, null);
        // v0.4 wake word — now enabled, foreground hotword loop
        settingRow(behav, "Wake word",      "'hey harness' listening",      "set_wake", false, true, null);
        // observe → act vision agent (Android 11+ with Accessibility enabled)
        settingRow(behav, "Vision agent",   "drives the screen with screenshots", "set_vision", false, true, null);
        body.addView(behav);

        body.addView(sectionGap("permissions"));
        LinearLayout perms = col(VERT);
        perms.setBackground(box(R_MD, SC_LO, BORDER));
        perms.setElevation(dp(1));
        pad(perms, 14, 6, 14, 6);
        permRow(perms, "Modify system settings", Settings.System.canWrite(this));
        permRow(perms, "Accessibility service",  HarnessService.isEnabled());
        permRow(perms, "Microphone",
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED);
        permRow(perms, "Query all packages",     true);
        body.addView(perms);

        body.addView(sectionGap("engine"));
        LinearLayout eng = col(VERT);
        eng.setBackground(box(R_MD, SC_LO, BORDER));
        eng.setElevation(dp(1));
        pad(eng, 14, 10, 14, 10);
        AIClient.Cfg aiCfg = AIClient.load(sp);
        String buildName = "0.5-gold";
        try {
            buildName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        engineRow(eng, "build",   buildName, false);
        engineRow(eng, "planner",
                aiCfg.ready() ? "ai · " + aiCfg.shortModel() + " · " + aiCfg.mode
                              : "rules v1 · offline",
                aiCfg.enabled && !aiCfg.ready());
        body.addView(eng);

        body.addView(sectionGap("ai engine · works on mobile data"));
        LinearLayout ai = col(VERT);
        ai.setBackground(box(R_MD, SC_LO, BORDER));
        ai.setElevation(dp(1));
        pad(ai, 14, 6, 14, 12);

        // one-tap presets: the phone calls these DIRECTLY — no PC needed.
        // GO = opencode Go route (native opencode server, same as SELF-HOST but explicit)
        HorizontalScrollView phs = new HorizontalScrollView(this);
        phs.setHorizontalScrollBarEnabled(false);
        LinearLayout presets = col(HORIZ);
        pad(presets, 0, 2, 0, 6);
        presetChip(presets, "ZEN",       "https://opencode.ai/zen/v1",     "openai", "nemotron-3-ultra-free");
        presetChip(presets, "OPENROUTER","https://openrouter.ai/api/v1",  "openai", "");
        presetChip(presets, "GO",        "https://opencode.ai/zen/go/v1", "openai", "glm-5.3-flash");
        presetChip(presets, "SELF-HOST", "",                              "opencode", "");
        phs.addView(presets, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ai.addView(phs, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        settingRow(ai, "AI planner", "plans multi-step requests · called from this phone", "set_ai", false, true, null);
        valueRow(ai, "endpoint", aiCfg.endpoint.isEmpty() ? "not set" : aiCfg.endpoint,
                 "https://your-provider · or LAN opencode serve", "ai_ep");
        valueRow(ai, "model", aiCfg.model.isEmpty()
                        ? (aiCfg.mode.equals("opencode") ? "provider/model" : "model id")
                        : aiCfg.model,
                 aiCfg.mode.equals("opencode")
                         ? "anthropic/claude-sonnet-4  (provider/model)"
                         : "e.g. qwen2.5-coder:7b",
                 "ai_model");
        String masked = aiCfg.apiKey.isEmpty() ? "none"
                : aiCfg.apiKey.length() <= 6 ? "•••"
                : aiCfg.apiKey.substring(0, 3) + "…" + aiCfg.apiKey.substring(aiCfg.apiKey.length() - 2);
        valueRow(ai, "api key", masked, "optional · sent as Bearer token", "ai_key");
        valueRow(ai, "transport",
                 aiCfg.mode.equals("opencode") ? "opencode server api" : "openai-compatible",
                 "tap to switch transport", "ai_mode");
        LinearLayout testLine = col(HORIZ);
        pad(testLine, 0, 8, 0, 2);
        testLine.setGravity(Gravity.CENTER_VERTICAL);
        Button testBtn = button("Test connection", false);
        testBtn.setOnClickListener(v -> runAiTest());
        testLine.addView(testBtn);
        final TextView testStat = tv("probes reachability · auth · parsing", 11, OUTLINE, false, true);
        aiTestStat = testStat;   // wire the field — the button was a silent no-op without this
        pad(testStat, 12, 0, 0, 0);
        testLine.addView(testStat, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ai.addView(testLine);
        body.addView(ai);

        root.addView(scrollHost(body), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    void settingRow(LinearLayout parent, String name, String sub, final String key,
                    boolean defOn, boolean enabled, String tag) {
        LinearLayout row = col(HORIZ);
        row.setGravity(Gravity.CENTER_VERTICAL);
        pad(row, 0, 13, 0, 13);

        LinearLayout mid = col(VERT);
        LinearLayout nl = col(HORIZ);
        nl.setGravity(Gravity.CENTER_VERTICAL);
        nl.addView(tv(name, 15, ON_SURF, false, false));
        if (tag != null) {
            TextView tg = tv(tag, 10, AMBER, true, true);
            tg.setBackground(box(999, Color.TRANSPARENT, BORDER));
            pad(tg, 8, 1, 8, 1);
            LinearLayout.LayoutParams tlp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(8);
            nl.addView(tg, tlp);
        }
        mid.addView(nl);
        TextView sb = tv(sub, 12, MUTED, false, false);
        pad(sb, 0, 2, 0, 0);
        mid.addView(sb);
        row.addView(mid, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Toggle tog = new Toggle(enabled);
        tog.setOn(sp.getBoolean(key, defOn));
        if (!enabled) tog.setAlpha(0.4f);
        tog.setOnClickListener(v -> {
            boolean now = !tog.isOn();
            tog.setOn(now);
            sp.edit().putBoolean(key, now).apply();
            if ("set_wake".equals(key)) {
                if (now) ensureMicAndStartWake(); else stopWake();
                // refresh header dot if on console
                if (wakeDot != null) wakeDot.setBackground(box(999, now && wakeListening ? CYAN : BORDER, 0));
            }
            if ("set_tts".equals(key) && now) ensureTts();
        });
        row.addView(tog, lp(dp(36), dp(20)));
        parent.addView(row);
    }

    void permRow(LinearLayout parent, String name, boolean granted) {
        LinearLayout row = col(HORIZ);
        row.setGravity(Gravity.CENTER_VERTICAL);
        pad(row, 0, 13, 0, 13);
        row.addView(tv(name, 15, ON_SURF, false, false), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(stat(granted ? "granted" : "off", granted ? CYAN : AMBER, false));
        parent.addView(row);
    }

    void engineRow(LinearLayout parent, String k, String v, boolean amber) {
        LinearLayout row = col(HORIZ);
        row.setGravity(Gravity.CENTER_VERTICAL);
        pad(row, 0, 4, 0, 4);
        row.addView(tv(k, 12, MUTED, false, true));
        TextView val = tv(v, 12, amber ? AMBER : ON_SURF, false, true);
        val.setSingleLine(true);
        val.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        val.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = dp(12);
        row.addView(val, vlp);
        parent.addView(row);
    }

    /** settings row showing a mono value; tap to edit (transport row toggles) */
    void valueRow(LinearLayout parent, final String name, String value,
                  final String hint, final String key) {
        LinearLayout row = col(HORIZ);
        row.setGravity(Gravity.CENTER_VERTICAL);
        pad(row, 0, 11, 0, 11);
        row.addView(tv(name, 13, MUTED, false, true),
                lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.30f));
        boolean empty = value == null || value.isEmpty() || "not set".equals(value)
                || "none".equals(value) || "provider/model".equals(value) || "model id".equals(value);
        TextView val = tv(value, 12, empty ? OUTLINE : ON_SURF, false, true);
        val.setEllipsize(android.text.TextUtils.TruncateAt.START);
        val.setSingleLine(true);
        row.addView(val, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.70f));
        row.setOnClickListener(v -> {
            if ("ai_mode".equals(key)) {
                String cur = sp.getString("ai_mode", "opencode");
                sp.edit().putString("ai_mode", "opencode".equals(cur) ? "openai" : "opencode").apply();
                showScreen("settings");
            } else if ("ai_model".equals(key)) {
                showModelPicker();
            } else {
                editValueDialog(name, key, hint);
            }
        });
        parent.addView(row);
    }

    /** one-tap endpoint preset: writes endpoint+mode, enables planner, refreshes */
    void presetChip(LinearLayout parent, final String label,
                    final String ep, final String mode, final String model) {
        TextView c = tv(label, 11, INDIGO_HI, true, true);
        c.setBackground(box(999, Color.TRANSPARENT, BORDER));
        pad(c, 12, 5, 12, 5);
        LinearLayout.LayoutParams clp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(8);
        c.setOnClickListener(v -> {
            android.content.SharedPreferences.Editor ed = sp.edit();
            if (!ep.isEmpty()) ed.putString("ai_ep", ep); else ed.remove("ai_ep");
            ed.putString("ai_mode", mode);
            if (!model.isEmpty()) ed.putString("ai_model", model); else ed.remove("ai_model");
            if (!ep.isEmpty() && !model.isEmpty()) ed.remove("ai_key"); // free cloud tiers need no key
            ed.putBoolean("ai_on", true);
            ed.apply();
            showScreen("settings");
        });
        parent.addView(c, clp);
    }

    List<String> loadModelsCache() {
        List<String> out = new ArrayList<>();
        for (String s1 : sp.getString("ai_models_cache", "").split("\n"))
            if (!s1.trim().isEmpty()) out.add(s1.trim());
        return out;
    }

    /** dropdown of models fetched LIVE from the configured engine */
    void showModelPicker() {
        final AIClient.Cfg cfg = AIClient.load(sp);
        if (cfg.endpoint.isEmpty()) {
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                    .setTitle("select model")
                    .setMessage("pick an endpoint preset first (ZEN / OPENROUTER / SELF-HOST)")
                    .setPositiveButton("ok", null).create();
            d.show();
            if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(box(R_LG, SC_LOW, BORDER));
            return;
        }
        final android.app.AlertDialog wait = new android.app.AlertDialog.Builder(this)
                .setTitle("select model")
                .setMessage("fetching models from\n" + cfg.endpoint + " …")
                .setCancelable(true)
                .create();
        wait.show();
        if (wait.getWindow() != null) wait.getWindow().setBackgroundDrawable(box(R_LG, SC_LOW, BORDER));
        AIClient.listModels(cfg, new AIClient.ModelsCallback() {
            @Override public void onModels(final List<String> ids) {
                ui.post(() -> {
                    try { wait.dismiss(); } catch (Exception ignored) { }
                    sp.edit().putString("ai_models_cache",
                            android.text.TextUtils.join("\n", ids)).apply();
                    modelPickDialog(ids);
                });
            }
            @Override public void onError(String msg) {
                ui.post(() -> {
                    try { wait.dismiss(); } catch (Exception ignored) { }
                    List<String> cached = loadModelsCache();
                    if (!cached.isEmpty()) modelPickDialog(cached);   // stale but usable
                    else editValueDialog("model", "ai_model",
                            "couldn't list models (" + msg + ") — type it");
                });
            }
        });
    }

    void modelPickDialog(final List<String> ids) {
        final String current = sp.getString("ai_model", "");
        LinearLayout wrap = col(VERT);
        pad(wrap, 6, 2, 6, 2);
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setDivider(null);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, ids) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                String item = ids.get(pos);
                boolean sel = item.equals(current);
                t.setText(item);
                t.setTextColor(sel ? INDIGO_HI : ON_SURF);
                t.setTypeface(fMono, sel ? Typeface.BOLD : Typeface.NORMAL);
                t.setTextSize(13);
                t.setBackground(box(R_SM, sel ? 0x266366F1 : Color.TRANSPARENT, Color.TRANSPARENT));
                pad(t, 12, 11, 12, 11);
                return t;
            }
        };
        lv.setAdapter(ad);
        wrap.addView(lv, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)));
        TextView manual = tv("✎  enter manually…", 12, OUTLINE, false, true);
        manual.setGravity(Gravity.CENTER);
        pad(manual, 0, 10, 0, 8);
        wrap.addView(manual);
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("select model")
                .setView(wrap)
                .setNegativeButton("cancel", null)
                .create();
        lv.setOnItemClickListener((p2, v2, pos, id3) -> {
            sp.edit().putString("ai_model", ids.get(pos)).apply();
            dlg.dismiss();
            showScreen("settings");
        });
        manual.setOnClickListener(v -> {
            dlg.dismiss();
            editValueDialog("model", "ai_model", "type the exact model id");
        });
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(box(R_LG, SC_LOW, BORDER));
    }

    void editValueDialog(final String name, final String key, String hint) {
        LinearLayout wrap = col(VERT);
        pad(wrap, 22, 18, 22, 4);
        TextView lbl = tv(hint, 11, OUTLINE, false, true);
        wrap.addView(lbl);
        final EditText in = new EditText(this);
        in.setText(sp.getString(key, ""));
        in.setTextColor(ON_SURF);
        in.setHintTextColor(OUTLINE);
        in.setTextSize(13);
        in.setTypeface(fMono);
        in.setBackground(box(R_SM, SC_LO, BORDER));
        in.setSingleLine(true);
        pad(in, 10, 9, 10, 9);
        LinearLayout.LayoutParams ilp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(10);
        wrap.addView(in, ilp);

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(name)
                .setView(wrap)
                .setPositiveButton("save", (d, w) -> {
                    sp.edit().putString(key, in.getText().toString().trim()).apply();
                    showScreen("settings");
                })
                .setNegativeButton("cancel", null)
                .create();
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(box(R_LG, SC_LOW, BORDER));
            dlg.getWindow().setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    boolean aiTesting = false;

    /** settings → Test connection: probes reachability, auth and parsing */
    void runAiTest() {
        if (aiTesting) return;                      // ignore double-taps
        final AIClient.Cfg cfg = AIClient.load(sp);
        if (aiTestStat == null) return;
        if (!cfg.ready()) {                         // fail fast with guidance, no network
            aiTestStat.setTextColor(AMBER);
            aiTestStat.setText("! set endpoint"
                    + ("opencode".equals(cfg.mode) ? " (provider/model)" : " + model") + " first");
            return;
        }
        aiTesting = true;
        aiTestStat.setTextColor(INDIGO_HI);
        aiTestStat.setText("probing " + cfg.endpoint + "…");
        AIClient.test(cfg, new AIClient.Callback() {
            @Override public void onPlan(List<String> clauses, long ms) {
                ui.post(() -> { aiTesting = false; if (aiTestStat != null) {
                    aiTestStat.setTextColor(CYAN);
                    aiTestStat.setText("✓ alive · " + clauses.size() + " steps · " + (ms / 1000.0) + " s");
                }});
            }
            @Override public void onError(String msg, long ms) {
                ui.post(() -> { aiTesting = false; if (aiTestStat != null) {
                    aiTestStat.setTextColor(RED);
                    aiTestStat.setText("✗ " + msg);
                }});
            }
        });
    }

    // =============================================================== plan sheet ==
    void buildSheetOverlay(FrameLayout root) {
        backdrop = new FrameLayout(this);
        backdrop.setBackgroundColor(0x8C000000);
        backdrop.setVisibility(View.GONE);
        backdrop.setOnClickListener(v -> closeSheet());
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        sheet = col(VERT);
        sheet.setBackground(box(R_LG, SC_LOW, BORDER));
        // NOTE: no elevation here — API 28+ surface-tint washes SC_LOW into gray.
        // The scrim backdrop already provides the modal depth separation.
        pad(sheet, 18, 14, 18, 16);

        View grab = new View(this);
        grab.setBackground(box(999, SC_HIGH, 0));
        LinearLayout.LayoutParams glp = lp(dp(36), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(14);
        sheet.addView(grab, glp);

        // header: caps section label + live status chip
        LinearLayout head = col(HORIZ);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(caps("execution plan"), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sheetStatBox = col(HORIZ);
        head.addView(sheetStatBox);
        sheet.addView(head);

        // command readout: mono terminal line in an inset code chip
        planCmd = tv("", 12, ON_SURF, false, true);
        planCmd.setBackground(box(R_SM, SC_LO, BORDER));
        pad(planCmd, 12, 10, 12, 10);
        LinearLayout.LayoutParams cblp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cblp.topMargin = dp(12);
        cblp.bottomMargin = dp(4);
        sheet.addView(planCmd, cblp);

        stepsBox = col(VERT);
        stepsBox.setPadding(0, dp(6), 0, 0);
        sheet.addView(stepsBox);

        LinearLayout foot = col(HORIZ);
        pad(foot, 2, 14, 2, 0);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        runMeta = tv("standing by", 10, OUTLINE, false, true);
        foot.addView(runMeta, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button dismiss = button("Dismiss", false);
        dismiss.setOnClickListener(v -> closeSheet());
        foot.addView(dismiss);
        sheet.addView(foot, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // floats above the nav strip as a rounded card
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        slp.bottomMargin = dp(68);
        slp.leftMargin = dp(10);
        slp.rightMargin = dp(10);
        sheet.setTranslationY(dp(900));
        root.addView(sheet, slp);
    }

    // =========================================================== voice hold-to-talk ==
    void buildVoiceOverlay(FrameLayout root) {
        voiceBackdrop = new FrameLayout(this);
        voiceBackdrop.setBackgroundColor(0x99000000);
        voiceBackdrop.setVisibility(View.GONE);
        voiceBackdrop.setOnClickListener(v -> dismissVoice());
        root.addView(voiceBackdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        voiceSheet = col(VERT);
        voiceSheet.setBackground(box(R_LG, SC_LOW, BORDER));
        pad(voiceSheet, 18, 12, 18, 16);

        View grab = new View(this);
        grab.setBackground(box(999, SC_HIGH, 0));
        LinearLayout.LayoutParams glp = lp(dp(36), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(12);
        voiceSheet.addView(grab, glp);

        // header: caps + live status
        LinearLayout head = col(HORIZ);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(caps("voice input"), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        voiceStatBox = col(HORIZ);
        head.addView(voiceStatBox);
        voiceSheet.addView(head);

        // orb hero — same Orb, larger stage with subtitle
        LinearLayout orbStage = col(VERT);
        orbStage.setGravity(Gravity.CENTER_HORIZONTAL);
        pad(orbStage, 0, 16, 0, 10);
        FrameLayout orbWrap = new FrameLayout(this);
        orbWrap.addView(new Orb(this), new FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER));
        ImageView mic = icon(R.drawable.ic_mic, INDIGO_HI, 34);
        orbWrap.addView(mic, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        orbStage.addView(orbWrap, new FrameLayout.LayoutParams(dp(92), dp(92)));

        // waveform: 5 bars
        voiceBars = col(HORIZ);
        voiceBars.setGravity(Gravity.CENTER);
        pad(voiceBars, 0, 14, 0, 0);
        for (int i = 0; i < 5; i++) {
            View bar = new View(this);
            bar.setBackground(box(999, INDIGO_HI, 0));
            LinearLayout.LayoutParams blp = lp(dp(3), dp(14));
            blp.leftMargin = dp(3); blp.rightMargin = dp(3);
            bar.setTag(blp.height);
            voiceBars.addView(bar, blp);
        }
        orbStage.addView(voiceBars);
        TextView listenCap = tv("listening… speak now", 10, OUTLINE, true, true);
        listenCap.setLetterSpacing(0.06f); listenCap.setAllCaps(true);
        pad(listenCap, 0, 8, 0, 0);
        orbStage.addView(listenCap);
        voiceSheet.addView(orbStage);

        // transcript chip
        voiceTranscript = tv("Try “open youtube and play tu hi to hai”", 13, MUTED, false, false);
        voiceTranscript.setBackground(box(R_SM, SC_LO, BORDER));
        voiceTranscript.setGravity(Gravity.CENTER);
        pad(voiceTranscript, 14, 12, 14, 12);
        LinearLayout.LayoutParams tlp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(4);
        voiceSheet.addView(voiceTranscript, tlp);

        voiceHint = tv("Release to send · tap outside to cancel", 10, OUTLINE, false, true);
        voiceHint.setGravity(Gravity.CENTER);
        voiceHint.setLetterSpacing(0.05f);
        pad(voiceHint, 0, 8, 0, 0);
        voiceSheet.addView(voiceHint);

        LinearLayout foot = col(HORIZ);
        pad(foot, 0, 14, 0, 0);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView tip = tv("powered by Android recognizer", 10, OUTLINE, false, true);
        foot.addView(tip, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button cancel = button("Cancel", false);
        cancel.setOnClickListener(v -> dismissVoice());
        foot.addView(cancel);
        voiceSheet.addView(foot, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        vlp.bottomMargin = dp(68);
        vlp.leftMargin = dp(10);
        vlp.rightMargin = dp(10);
        voiceSheet.setTranslationY(dp(900));
        root.addView(voiceSheet, vlp);
    }

    void setVoiceStatus(String label, int color, boolean pulse) {
        if (voicePulse != null) { voicePulse.cancel(); voicePulse = null; }
        voiceStatBox.removeAllViews();
        TextView t = tv(label, 10, color, true, true);
        t.setLetterSpacing(0.08f); t.setAllCaps(true);
        t.setCompoundDrawablesWithIntrinsicBounds(mkDot(color), null, null, null);
        t.setCompoundDrawablePadding(dp(6));
        LinearLayout pill = col(HORIZ);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(box(999, SC_LO, BORDER));
        pad(pill, 10, 5, 12, 5);
        pill.addView(t);
        voiceStatBox.addView(pill);
        if (pulse) {
            final android.graphics.drawable.Drawable d = t.getCompoundDrawables()[0];
            ValueAnimator a = ValueAnimator.ofInt(240, 70);
            a.setDuration(520); a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.addUpdateListener(an -> { if (t.isAttachedToWindow()) d.setAlpha((int) an.getAnimatedValue()); });
            a.start(); voicePulse = a;
        }
    }

    void animateVoiceBars(boolean on) {
        if (voiceBarsAnim != null) { voiceBarsAnim.cancel(); voiceBarsAnim = null; }
        if (!on || voiceBars == null) return;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(420); a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            for (int i = 0; i < voiceBars.getChildCount(); i++) {
                View bar = voiceBars.getChildAt(i);
                // staggered heights: middle bars peak higher
                float phase = (i == 2) ? f : (i == 1 || i == 3) ? f * 0.7f : f * 0.45f;
                int h = dp(6) + Math.round(phase * (i == 2 ? 18 : 12));
                ViewGroup.LayoutParams lp2 = bar.getLayoutParams();
                lp2.height = h; bar.setLayoutParams(lp2);
                bar.setAlpha(0.6f + phase * 0.4f);
            }
        });
        a.start(); voiceBarsAnim = a;
    }

    void showVoicePopup() {
        voiceTranscript.setText("Try “open youtube and play tu hi to hai”");
        voiceTranscript.setTextColor(MUTED);
        voiceHint.setText("Release to send · tap outside to cancel");
        setVoiceStatus("listening", CYAN, true);
        animateVoiceBars(true);
        voiceBackdrop.setVisibility(View.VISIBLE);
        voiceBackdrop.setAlpha(0f);
        voiceBackdrop.animate().alpha(1f).setDuration(180).start();
        voiceSheet.animate().translationY(0).setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    void dismissVoice() {
        stopVoiceListening();
        if (voiceBarsAnim != null) { voiceBarsAnim.cancel(); voiceBarsAnim = null; }
        if (voicePulse != null) { voicePulse.cancel(); voicePulse = null; }
        voiceSheet.animate().translationY(voiceSheet.getHeight() + dp(200)).setDuration(220).start();
        voiceBackdrop.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    voiceBackdrop.setVisibility(View.GONE);
                    // resume wake after voice done — always restart even if voice never started
                    if (wakePausedForVoice) {
                        wakePausedForVoice = false;
                        wakeListening = false;
                        if (sp.getBoolean("set_wake", false)) {
                            android.util.Log.i("PH_WAKE", "resuming after voice");
                            ui.postDelayed(() -> startWake(), 500);
                        }
                    } else if (sp.getBoolean("set_wake", false) && !wakeListening) {
                        // safety: voice dismissed without wake pause (e.g. manual tap) — ensure wake alive
                        ui.postDelayed(() -> startWake(), 500);
                    }
                }).start();
    }

    // ============================================================ v0.4 wake word ==
    void ensureMicAndStartWake() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingVoiceAction = "wake";
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                return;
            }
        }
        startWake();
    }

    void startWake() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        if (wakeListening) return;
        try { if (wakeRecog != null) { wakeRecog.cancel(); wakeRecog.destroy(); } } catch (Exception ignored) {}
        wakeListening = true; wakeEnabled = true;
        if (wakeDot != null) wakeDot.setBackground(box(999, CYAN, 0));
        ensureTts();
        wakeRecog = SpeechRecognizer.createSpeechRecognizer(this);
        wakeRecog.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) {
                android.util.Log.i("PH_WAKE", "ready");
                if (wakeDot != null) wakeDot.setBackground(box(999, CYAN, 0));
            }
            public void onBeginningOfSpeech() { android.util.Log.i("PH_WAKE", "begin"); }
            public void onRmsChanged(float r) {}
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() { android.util.Log.i("PH_WAKE", "end"); }
            public void onError(int code) {
                android.util.Log.w("PH_WAKE", "error " + code);
                if (!wakeListening) return;
                long delay = (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) ? 350 : 800;
                // MIUI network-busy errors: retry with backoff, show hint once
                if (code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_SERVER) {
                    android.util.Log.w("PH_WAKE", "network/server error — need Google + internet");
                }
                wakeHandler.removeCallbacks(wakeRestart);
                wakeHandler.postDelayed(wakeRestart, delay);
            }
            public void onResults(Bundle r) {
                if (!wakeListening) return;
                ArrayList<String> got = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                android.util.Log.i("PH_WAKE", "results: " + got);
                boolean hit = false; String best = "";
                if (got != null) for (String s : got) { if (isWakePhrase(s)) { hit = true; best = s; break; } if (best.isEmpty()) best = s; }
                if (hit) onWakeTriggered(best);
                else {
                    wakeHandler.removeCallbacks(wakeRestart);
                    wakeHandler.postDelayed(wakeRestart, 300);
                }
            }
            public void onPartialResults(Bundle p) {
                if (!wakeListening) return;
                ArrayList<String> got = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                android.util.Log.i("PH_WAKE", "partial: " + got);
                if (got != null) for (String s : got) if (isWakePhrase(s)) { onWakeTriggered(s); break; }
            }
            public void onEvent(int e, Bundle b) {}
        });
        wakeRestart = () -> { if (wakeListening) doWakeListen(); };
        doWakeListen();
    }

    void doWakeListen() {
        if (!wakeListening || wakeRecog == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try { wakeRecog.startListening(i); }
        catch (Exception e) {
            wakeHandler.postDelayed(wakeRestart, 1000);
        }
    }

    boolean isWakePhrase(String raw) {
        if (raw == null) return false;
        String s = raw.toLowerCase(java.util.Locale.US).trim().replaceAll("\\s+", " ");
        String norm = s.replaceAll("h+", "h").replaceAll("y+", "y"); // hhey→hey stutter fix
        // direct + stutter-tolerant
        if (norm.contains("hey harness") || s.contains("hey harness")
                || norm.contains("hay harness") || s.contains("hhey harness")) return true;
        // regex: h?hey with stutter, 0-8 chars, harn
        if (norm.matches(".*h?ey.{0,8}harn\\w*.*") || s.matches(".*h+ey.{0,8}harn\\w*.*")) return true;
        // any hey/hay/hhey + harness fragment in same utterance
        if ((norm.contains("hey") || norm.contains("hay") || norm.contains("hhey")) && norm.contains("harn")) return true;
        if ((s.contains("hey") || s.contains("hay")) && s.contains("harn")) return true;
        // solo "harness" is enough when wake is on (user mumbles)
        if (s.equals("harness") || norm.equals("harness")) return true;
        // lev on short utterances — allow 4 edits for stutter
        if (s.length() <= 24) {
            int d = lev(norm, "hey harness");
            if (d <= 4) return true;
            if (lev(s, "hey harness") <= 4) return true;
        }
        android.util.Log.i("PH_WAKE", "no wake: \"" + raw + "\" -> norm=\"" + norm + "\"");
        return false;
    }

    int lev(String a, String b) {
        int[][] dp = new int[a.length()+1][b.length()+1];
        for (int i=0;i<=a.length();i++) dp[i][0]=i;
        for (int j=0;j<=b.length();j++) dp[0][j]=j;
        for (int i=1;i<=a.length();i++) for (int j=1;j<=b.length();j++)
            dp[i][j]=Math.min(Math.min(dp[i-1][j]+1, dp[i][j-1]+1), dp[i-1][j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
        return dp[a.length()][b.length()];
    }

    void onWakeTriggered(String heard) {
        if (wakePausedForVoice) return;
        android.util.Log.i("PH_WAKE", "TRIGGERED by \"" + heard + "\"");
        voiceBusyRetries = 0;
        wakePausedForVoice = true;
        wakeListening = false; // allow startWake() to recreate after voice
        wakeHandler.removeCallbacks(wakeRestart);
        try { if (wakeRecog != null) { wakeRecog.cancel(); wakeRecog.destroy(); wakeRecog=null; } } catch (Exception ignored) {}
        if (wakeDot != null) wakeDot.setBackground(box(999, AMBER, 0));
        if (sp.getBoolean("set_haptic", true)) {
            try { ((android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(80); } catch (Exception ignored) {}
        }
        if (sp.getBoolean("set_tts", false)) speak("Yes?");
        showVoicePopup();
        voiceTranscript.setText("heard “" + heard.trim() + "” · listening…");
        voiceTranscript.setTextColor(CYAN);
        setVoiceStatus("wake!", CYAN, true);
        // give mic 450ms to release from wake before voice grabs it (busy→error 8)
        ui.postDelayed(() -> ensureMicAndStartVoice(), 450);
    }

    void stopWake() {
        wakeListening = false; wakeEnabled = false;
        wakeHandler.removeCallbacks(wakeRestart);
        try { if (wakeRecog != null) { wakeRecog.cancel(); wakeRecog.destroy(); wakeRecog=null; } } catch (Exception ignored) {}
        if (wakeDot != null) wakeDot.setBackground(box(999, BORDER, 0));
    }

    void ensureTts() {
        if (tts != null) return;
        try {
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) tts.setLanguage(java.util.Locale.US);
            });
        } catch (Exception ignored) {}
    }

    void speak(String txt) {
        ensureTts();
        try { if (tts != null) tts.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "wake"); } catch (Exception ignored) {}
        // also post visual confirmation to history? no — keep silent
    }

    @Override protected void onResume() {
        super.onResume();
        syncPermFlags();
        if ("onboard".equals(currentScreen)) showScreen("onboard"); // reflect grants on return
        if (sp != null && sp.getBoolean("set_wake", false) && !wakeListening && !wakePausedForVoice) {
            // resume wake after short delay (let UI settle)
            wakeHandler.postDelayed(() -> ensureMicAndStartWake(), 500);
        }
    }

    @Override protected void onPause() {
        // keep wake alive only while activity is foreground — stop to save mic/battery
        if (wakeListening && !wakePausedForVoice) {
            try { if (wakeRecog != null) wakeRecog.cancel(); } catch (Exception ignored) {}
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopWake();
        ui.removeCallbacksAndMessages(null);
        wakeHandler.removeCallbacksAndMessages(null);
        runner.shutdownNow();
        try { if (speechRecog != null) { speechRecog.cancel(); speechRecog.destroy(); } } catch (Exception ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) {}
        super.onDestroy();
    }

    class StepRow {
        LinearLayout root;
        View dot, rail;
        TextView name, meta;
    }

    /** timeline step: state dot + connector rail + numbered label */
    StepRow addStep(String idx, String nameText, String metaText) {
        StepRow s = new StepRow();
        s.root = col(HORIZ);
        pad(s.root, 0, 8, 0, 8);

        LinearLayout gutter = col(VERT);
        gutter.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable dg = box(999, Color.TRANSPARENT, BORDER);
        s.dot = new View(this);
        s.dot.setBackground(dg);
        s.dot.setTag(dg);
        gutter.addView(s.dot, lp(dp(13), dp(13)));
        s.rail = new View(this);
        s.rail.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams rlp = lp(dp(1), 0, 1f);
        rlp.topMargin = dp(5);
        gutter.addView(s.rail, rlp);
        LinearLayout.LayoutParams gutlp = lp(dp(15), ViewGroup.LayoutParams.MATCH_PARENT);
        gutlp.rightMargin = dp(12);
        s.root.addView(gutter, gutlp);

        LinearLayout mid = col(VERT);
        LinearLayout nr = col(HORIZ);
        nr.setGravity(Gravity.CENTER_VERTICAL);
        TextView num = tv(idx, 10, OUTLINE, true, true);
        num.setPadding(0, 0, dp(8), dp(1));
        nr.addView(num);
        s.name = tv(nameText, 13, ON_SURF, true, false);
        nr.addView(s.name);
        mid.addView(nr);
        s.meta = tv(metaText, 10, OUTLINE, false, true);
        s.meta.setMaxLines(2);
        s.meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pad(s.meta, 0, 3, 0, 0);
        mid.addView(s.meta);
        s.root.addView(mid, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stepsBox.addView(s.root);
        return s;
    }

    void stepState(final StepRow s, final String mode, final String metaText) {
        ui.post(() -> {
            Object tag = s.dot.getTag();
            if (!(tag instanceof GradientDrawable)) return;
            GradientDrawable dg = (GradientDrawable) tag;
            switch (mode) {
                case "run":
                    dg.setColor(0x266366F1); dg.setStroke(2, INDIGO);
                    s.meta.setText(metaText.isEmpty() ? "running…" : metaText);
                    s.meta.setTextColor(INDIGO_HI);
                    break;
                case "done":
                    dg.setColor(CYAN); dg.setStroke(1, CYAN);
                    s.meta.setText(metaText);
                    s.meta.setTextColor(CYAN);
                    break;
                case "fail":
                    dg.setColor(RED); dg.setStroke(1, RED);
                    s.meta.setText(metaText);
                    s.meta.setTextColor(RED);
                    break;
                default: break;
            }
        });
    }

    /** pill status chip: colored caps label + pulsing state dot */
    void setStatusChip(String label, int color, boolean pulse) {
        if (chipPulse != null) { chipPulse.cancel(); chipPulse = null; }
        sheetStatBox.removeAllViews();
        TextView t = tv(label, 10, color, true, true);
        t.setLetterSpacing(0.08f);
        t.setAllCaps(true);
        t.setCompoundDrawablesWithIntrinsicBounds(mkDot(color), null, null, null);
        t.setCompoundDrawablePadding(dp(6));
        LinearLayout pill = col(HORIZ);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(box(999, SC_LO, BORDER));
        pad(pill, 10, 5, 12, 5);
        pill.addView(t);
        sheetStatBox.addView(pill);
        if (pulse) {
            final android.graphics.drawable.Drawable d = t.getCompoundDrawables()[0];
            ValueAnimator a = ValueAnimator.ofInt(240, 70);
            a.setDuration(520);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.addUpdateListener(an -> { if (t.isAttachedToWindow()) d.setAlpha((int) an.getAnimatedValue()); });
            a.start();
            chipPulse = a;
        }
    }

    void openSheet(String cmd) {
        runSeq++;
        chatSavedForRun = false;
        planCmd.setText("> " + cmd);
        runMeta.setText("executing…");
        setStatusChip("running", INDIGO_HI, true);
        stepsBox.removeAllViews();
        backdrop.setVisibility(View.VISIBLE);
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(180).start();
        sheet.animate().translationY(0).setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    void closeSheet() {
        if (chipPulse != null) { chipPulse.cancel(); chipPulse = null; }
        visionLoopActive = false; // dismiss cancels an in-flight vision agent
        sheet.animate().translationY(sheet.getHeight() + dp(200)).setDuration(220).start();
        backdrop.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> backdrop.setVisibility(View.GONE)).start();
    }

    /** after a successful run, surface the console again so the user lands on the result */
    void bringToConsole() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    // =================================================================== runner ==
    /** executes clauses sequentially on the main thread with staged timing,
     *  so intent fires (startActivity etc.) stay on the UI thread. */
    void runCommand() {
        final String cmd = cmdInput == null ? "" : cmdInput.getText().toString().trim();
        if (cmd.isEmpty()) return;
        if (cmdInput != null) cmdInput.setText("");

        final long t0 = SystemClock.elapsedRealtime();

        final AIClient.Cfg ai = AIClient.load(sp);
        android.util.Log.i("PH_AI", "cmd='" + cmd + "' ready=" + ai.ready()
                + " on=" + ai.enabled + " ep=" + ai.endpoint + " model=" + ai.model + " mode=" + ai.mode);
        if (ai.ready()) {
            // hybrid: rules execute every clause they understand; vision only runs for
            // on-screen actions (click/select/…) or when the user enabled the agent.
            boolean visionUsable = android.os.Build.VERSION.SDK_INT >= 30 && HarnessService.isEnabled();
            boolean visionAgent = sp.getBoolean("set_vision", false);
            boolean vision = visionUsable && (visionAgent || hasVisionClause(cmd));
            android.util.Log.i("PH_AI", "vision=" + vision + " (usable=" + visionUsable
                    + " agent=" + visionAgent + " clause=" + hasVisionClause(cmd) + ")");
            if (vision) {
                runVisionLoop(cmd, t0, ai);
                return;
            }
            runAiPlanned(cmd, t0, ai);
            return;
        }
        openSheet(cmd);
        final int seq = runSeq;
        final String[] rawClauses = harness.clauses(cmd);
        final List<String> cs = new ArrayList<>();
        for (String c : rawClauses) if (!c.trim().isEmpty()) cs.add(c.trim());

        final StepRow parseRow = addStep("01", "Parse intent",
                cs.size() + " clause" + (cs.size() > 1 ? "s" : "") + " · rules v1");

        final List<StepRow> rows = new ArrayList<>();
        for (int i = 0; i < cs.size(); i++)
            rows.add(addStep(String.format("%02d", i + 2), cs.get(i), "queued"));
        if (!rows.isEmpty()) rows.get(rows.size() - 1).rail.setVisibility(View.INVISIBLE);

        beginExecution(rows, cs, cmd, t0, seq);
        ui.postDelayed(() -> {
            final long[] acc = {Math.max(1, SystemClock.elapsedRealtime() - t0)};
            stepState(parseRow, "done", "rules v1 · " + acc[0] + " ms");
        }, 90);
    }

    boolean hasVisionClause(String cmd) {
        for (String c : harness.clauses(cmd)) if (isVisionClause(c)) return true;
        return false;
    }

    boolean isVisionClause(String c) {
        String l = c.trim().toLowerCase(java.util.Locale.US);
        return l.startsWith("click") || l.startsWith("select") || l.startsWith("choose")
                || l.startsWith("long press") || l.startsWith("long-press")
                || l.startsWith("find out") || l.startsWith("look up") || l.startsWith("read")
                || l.startsWith("check messages") || l.startsWith("check texts")
                || l.startsWith("check my messages") || l.startsWith("read messages")
                || l.startsWith("read texts") || l.startsWith("summarize");
    }

    boolean isVisionModel(AIClient.Cfg cfg) {
        String m = cfg.model.toLowerCase(java.util.Locale.US);
        return m.contains("vision") || m.contains("flash") || m.contains("muse") || m.contains("kimi") || m.contains("glm") || m.contains("grok") || m.contains("luna") || m.contains("qwen") || m.contains("minimax") || m.contains("hy");
    }

    // ================== observe → act vision agent ==================
    // screenshot → LLM picks ONE action → run it → screenshot again → repeat.

    void runVisionLoop(final String cmd, final long t0, final AIClient.Cfg ai) {
        openSheet(cmd);
        final int seq = runSeq;
        visionLoopActive = true;
        readAttempted = false;
        setStatusChip("vision", INDIGO_HI, true);
        runMeta.setText("vision agent · " + ai.shortModel());
        final List<String> history = new ArrayList<>();

        // hybrid: run the deterministic prefix through the rules engine first, then hand
        // the vision agent only the clause that needs eyes (click/select/…)
        final List<String> pre = new ArrayList<>();
        String goal = cmd;
        int vi = -1;
        final List<String> clauses = new ArrayList<>();
        for (String c : harness.clauses(cmd)) if (!c.trim().isEmpty()) clauses.add(c.trim());
        for (int i = 0; i < clauses.size(); i++)
            if (isVisionClause(clauses.get(i))) { vi = i; break; }
        if (vi >= 0) {
            for (int i = 0; i < vi; i++) pre.add(clauses.get(i));
            StringBuilder g = new StringBuilder();
            for (int i = vi; i < clauses.size(); i++) {
                if (g.length() > 0) g.append(" and ");
                g.append(clauses.get(i));
            }
            goal = g.toString();
        } else {
            String seed = firstOpenClause(cmd);
            if (seed != null) pre.add(seed);
        }
        final String fGoal = goal;
        ui.postDelayed(() -> {
            if (seq != runSeq || !visionLoopActive) return;
            HarnessService.globalHome();
            if (pre.isEmpty()) {
                ui.postDelayed(() -> visionStep(fGoal, t0, ai, seq, history, 1), 700);
                return;
            }
            final List<StepRow> rows = new ArrayList<>();
            for (int i = 0; i < pre.size(); i++)
                rows.add(addStep(String.format("%02d", i + 1), pre.get(i), "queued"));
            runner.execute(() -> {
                for (int i = 0; i < pre.size(); i++) {
                    final StepRow r = rows.get(i);
                    final String clause = pre.get(i);
                    ui.post(() -> { if (seq == runSeq) stepState(r, "run", ""); });
                    List<String> out = harness.executeOne(clause);
                    final String res = out.isEmpty() ? "" : out.get(0);
                    final boolean ok = !(res.startsWith("✗") || res.startsWith("?"));
                    ui.post(() -> {
                        if (seq != runSeq) return;
                        stepState(r, ok ? "done" : "fail", cleanRes(res));
                        history.add(clause + " → " + cleanRes(res));
                    });
                }
                ui.postDelayed(() -> {
                    if (seq == runSeq && visionLoopActive)
                        visionStep(fGoal, t0, ai, seq, history, pre.size() + 1);
                }, 400);
            });
        }, 1300);
    }

    String firstOpenClause(String cmd) {
        for (String c : harness.clauses(cmd)) {
            String t = c.trim().toLowerCase(java.util.Locale.US);
            if (t.startsWith("open ") || t.startsWith("launch ") || t.startsWith("start "))
                return c.trim();
        }
        return null;
    }

    String cleanRes(String res) {
        return res == null ? "" : res.replaceFirst("^[▸✗?]\\s*", "").trim();
    }

    String visionAgentPrompt(String cmd, List<String> history, List<HarnessService.Node> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("You control an Android phone by looking at screenshots.\n");
        sb.append("Goal: \"").append(cmd).append("\"\n");
        if (history.isEmpty()) {
            sb.append("This is the first screen. Decide the single next action.\n");
        } else {
            sb.append("Actions already taken:\n");
            int from = Math.max(0, history.size() - 5);
            for (int i = from; i < history.size(); i++) sb.append("- ").append(history.get(i)).append("\n");
            sb.append("If every part of the goal is visibly complete (including all clicks and typing), reply [\"done\"].\n");
        }
        if (nodes != null && !nodes.isEmpty()) {
            sb.append("Numbered boxes on the screenshot mark the clickable elements:\n");
            for (int i = 0; i < nodes.size(); i++) {
                HarnessService.Node n = nodes.get(i);
                String kind = n.editable ? "input" : n.scrollable ? "scroll" : "button";
                sb.append("#").append(i + 1).append(" ").append(kind);
                if (!n.label.isEmpty()) sb.append(" \"").append(n.label).append("\"");
                sb.append("\n");
            }
        }
        sb.append("Coordinates are 0-1000 fractions of the screenshot: x 0=left 1000=right, y 0=top 1000=bottom.\n");
        sb.append("Reply with EXACTLY ONE next action as a JSON array, e.g. [\"click #3\"].\n");
        sb.append("When the goal is to find out something, browse to the page with the answer, then reply [\"read\"] instead of clicking further — do not open images or unrelated links.\n");
        sb.append("Ignore app-install, update, sign-in and subscribe prompts; dismiss a blocking dialog with its close X, Cancel, or back — never press Install/Update.\n");
        sb.append("Allowed actions:\n");
        sb.append("- \"click #N\"   click numbered element N (preferred — exact, no coordinates)\n");
        sb.append("- \"open <app>\"   launch an app by name\n");
        sb.append("- \"tap <x> <y>\"  raw coordinates 0-1000, only if no numbered element matches\n");
        sb.append("- \"type <text>\"  type into the focused field (or \"type <text> into #N\")\n");
        sb.append("- \"press enter\"  submit / search\n");
        sb.append("- \"swipe up\" | \"swipe down\"  scroll\n");
        sb.append("- \"back\"  system back   ·   \"home\"  go home   ·   \"wait\"\n");
        sb.append("- \"read\"  read the page and save a short answer for the user\n");
        sb.append("- \"done\"  goal complete   ·   \"fail <why>\"  cannot proceed\n");
        sb.append("Reply with ONLY the JSON array. Do not explain or reason. No prose, no markdown fences.");
        return sb.toString();
    }

    void visionStep(final String cmd, final long t0, final AIClient.Cfg ai,
                    final int seq, final List<String> history, final int step) {
        if (!visionLoopActive || seq != runSeq) return;
        if (step > VISION_MAX_STEPS) {
            if (isResearch(cmd) && !readAttempted) {
                doRead(cmd, t0, ai, seq, history, step,
                        addStep(String.format("%02d", step), "Read page", "step limit"));
                return;
            }
            finishVision(cmd, t0, seq, history, false, "step limit reached");
            return;
        }
        setStatusChip("vision " + step + "/" + VISION_MAX_STEPS, INDIGO_HI, true);
        final StepRow row = addStep(String.format("%02d", step), "Observe · step " + step, "screenshot…");
        stepState(row, "run", "");

        // capture a clean screen (our overlay hidden)
        backdrop.setVisibility(View.GONE);
        sheet.setVisibility(View.GONE);
        ui.postDelayed(() -> HarnessService.screenshotMarked(new HarnessService.MarkedCb() {
            @Override public void onShot(final String b64, int w, int h, final java.util.List<HarnessService.Node> nodes) {
                ui.post(() -> {
                    if (seq != runSeq || !visionLoopActive) return;
                    backdrop.setVisibility(View.VISIBLE);
                    sheet.setVisibility(View.VISIBLE);
                    stepState(row, "run", "thinking…");
                    AIClient.stepWithImage(ai, visionAgentPrompt(cmd, history, nodes), b64, new AIClient.StepCb() {
                        @Override public void onAction(final String action, long ms) {
                            ui.post(() -> visionAct(cmd, t0, ai, seq, history, step, row, action));
                        }
                        @Override public void onError(final String msg, long ms) {
                            ui.post(() -> {
                                if (seq != runSeq) return;
                                stepState(row, "fail", msg);
                                finishVision(cmd, t0, seq, history, false, msg);
                            });
                        }
                    });
                });
            }
            @Override public void onError(final String msg) {
                ui.post(() -> {
                    if (seq != runSeq) return;
                    backdrop.setVisibility(View.VISIBLE);
                    sheet.setVisibility(View.VISIBLE);
                    stepState(row, "fail", msg);
                    finishVision(cmd, t0, seq, history, false, "no screenshot · " + msg);
                });
            }
        }), 400);
    }

    void visionAct(final String cmd, final long t0, final AIClient.Cfg ai, final int seq,
                   final List<String> history, final int step, final StepRow row, final String action) {
        if (!visionLoopActive || seq != runSeq) return;
        final String a = action == null ? "" : action.trim();
        final String l = a.toLowerCase(java.util.Locale.US);
        if (l.equals("read") || ((l.equals("done") || l.isEmpty()) && isResearch(cmd))) {
            doRead(cmd, t0, ai, seq, history, step, row);
            return;
        }
        if (l.equals("done")) {
            stepState(row, "done", "goal reached");
            finishVision(cmd, t0, seq, history, true, "done");
            return;
        }
        if (l.isEmpty()) {
            stepState(row, "fail", "no action parsed");
            finishVision(cmd, t0, seq, history, false, "no action parsed");
            return;
        }
        if (l.startsWith("fail")) {
            stepState(row, "fail", a);
            finishVision(cmd, t0, seq, history, false, a);
            return;
        }
        final String shown = a.length() > 42 ? a.substring(0, 42) + "…" : a;
        stepState(row, "run", shown);
        runner.execute(() -> {
            List<String> out = harness.executeOne(a);
            final String res = cleanRes(out.isEmpty() ? "" : out.get(0));
            final boolean ok = !(out.isEmpty() || out.get(0).startsWith("?") || out.get(0).startsWith("✗"));
            ui.post(() -> {
                if (seq != runSeq || !visionLoopActive) return;
                stepState(row, ok ? "done" : "fail", shown + "  ·  " + res);
                history.add(a + " → " + res);
                ui.postDelayed(() -> visionStep(cmd, t0, ai, seq, history, step + 1), 500);
            });
        });
    }

    boolean isResearch(String cmd) {
        String l = cmd.toLowerCase(java.util.Locale.US);
        return l.contains("find out") || l.contains("who is") || l.contains("who was")
                || l.contains("what is") || l.contains("wikipedia")
                || l.contains("message") || l.contains("tell me");
    }

    void doRead(final String cmd, final long t0, final AIClient.Cfg ai, final int seq,
                final List<String> history, final int step, final StepRow row) {
        readAttempted = true;
        stepState(row, "run", "reading page…");
        runner.execute(() -> {
            final String text = HarnessService.pageText();
            if (text == null || text.trim().isEmpty()) {
                ui.post(() -> {
                    if (seq != runSeq || !visionLoopActive) return;
                    stepState(row, "fail", "no readable text on screen");
                    ui.postDelayed(() -> visionStep(cmd, t0, ai, seq, history, step + 1), 500);
                });
                return;
            }
            AIClient.answer(ai, cmd, text, new AIClient.AnswerCb() {
                @Override public void onAnswer(final String ans, long ms) {
                    ui.post(() -> {
                        if (seq != runSeq) return;
                        if (ans == null || ans.trim().isEmpty() || ans.trim().equalsIgnoreCase("not found")) {
                            stepState(row, "fail", "answer not on this page");
                            ui.postDelayed(() -> visionStep(cmd, t0, ai, seq, history, step + 1), 500);
                            return;
                        }
                        stepState(row, "done", "answer · " + ms + " ms");
                        history.add("read → " + ans);
                        saveAnswer(cmd, ans);
                        saveChat(cmd, ans);
                        finishVision(cmd, t0, seq, history, true, "answered");
                    });
                }
                @Override public void onError(final String msg, long ms) {
                    ui.post(() -> {
                        if (seq != runSeq || !visionLoopActive) return;
                        stepState(row, "fail", msg);
                        ui.postDelayed(() -> visionStep(cmd, t0, ai, seq, history, step + 1), 500);
                    });
                }
            });
        });
    }

    void finishVision(final String cmd, final long t0, final int seq, final List<String> history,
                      final boolean success, final String reason) {
        visionLoopActive = false;
        if (seq != runSeq) return;
        backdrop.setVisibility(View.VISIBLE);
        sheet.setVisibility(View.VISIBLE);
        final StringBuilder res = new StringBuilder();
        for (String h : history) {
            if (res.length() > 0) res.append("; ");
            res.append(h);
        }
        runMeta.setText(success ? "goal reached · " + history.size() + " actions" : reason);
        setStatusChip(success ? "complete" : "stopped", success ? CYAN : AMBER, false);
        saveRun(System.currentTimeMillis(), cmd, res.toString(), success ? "DONE" : "PARTIAL");
        if (!chatSavedForRun)
            saveChat(cmd, success ? "done · " + history.size() + " actions" : "stopped · " + reason);
        if (success) ui.postDelayed(() -> {
            if (seq == runSeq && backdrop.getVisibility() == View.VISIBLE) closeSheet();
            bringToConsole();
        }, 1800);
    }

    /** AI path: ask the opencode/openai planner for a clause list, then run it.
     *  Network happens off-thread; every UI touch is posted back to main. */
    void runAiPlanned(final String cmd, final long t0, final AIClient.Cfg ai) {
        openSheet(cmd);
        final int seq = runSeq;
        setStatusChip("planning", INDIGO_HI, true);
        runMeta.setText("asking " + ai.shortModel() + "…");
        final StepRow planRow = addStep("01", "Ask AI planner",
                ai.mode.equals("opencode") ? "opencode server · " + ai.shortModel()
                                           : "openai-compat · " + ai.shortModel());
        AIClient.plan(ai, cmd, harness.appLabels(), new AIClient.Callback() {
            @Override public void onPlan(final List<String> cs, long ms) {
                ui.post(() -> {
                    if (seq != runSeq) return;
                    stepState(planRow, "done", cs.size() + " steps · " + ms + " ms");
                    setStatusChip("running", INDIGO_HI, true);
                    final List<StepRow> rows = new ArrayList<>();
                    for (int i = 0; i < cs.size(); i++)
                        rows.add(addStep(String.format("%02d", i + 2), cs.get(i), "queued"));
                    if (!rows.isEmpty())
                        rows.get(rows.size() - 1).rail.setVisibility(View.INVISIBLE);
                    beginExecution(rows, cs, cmd, t0, seq);
                });
            }
            @Override public void onError(final String msg, long ms) {
                ui.post(() -> {
                    if (seq != runSeq) return;
                    stepState(planRow, "fail", msg + " · rules fallback");
                    // never dead-end: fall back to the offline rule splitter
                    final String[] rawClauses = harness.clauses(cmd);
                    final List<String> cs = new ArrayList<>();
                    for (String c : rawClauses) if (!c.trim().isEmpty()) cs.add(c.trim());
                    final List<StepRow> rows = new ArrayList<>();
                    for (int i = 0; i < cs.size(); i++)
                        rows.add(addStep(String.format("%02d", i + 2), cs.get(i), "queued"));
                    if (!rows.isEmpty())
                        rows.get(rows.size() - 1).rail.setVisibility(View.INVISIBLE);
                    setStatusChip("running", INDIGO_HI, true);
                    beginExecution(rows, cs, cmd, t0, seq);
                });
            }
        });
    }

    /** shared staged executor: runs each clause on the UI thread with pacing */
    void beginExecution(final List<StepRow> rows, final List<String> cs,
                        final String cmd, final long t0, final int seq) {
        final StringBuilder summary = new StringBuilder();
        final long[] acc = {0};
        final int[] idx = {0};
        final Runnable[] next = new Runnable[1];

        next[0] = () -> {
            if (idx[0] >= rows.size()) { finishRun(acc[0], cmd, summary.toString(), t0, seq); return; }
            final int i = idx[0]++;
            final StepRow row = rows.get(i);
            stepState(row, "run", "");
            ui.postDelayed(() -> runner.execute(() -> {
                final long s0 = SystemClock.elapsedRealtime();
                List<String> out;
                try { out = harness.executeOne(cs.get(i)); }
                catch (Throwable e) {
                    out = new ArrayList<>();
                    out.add("✗ " + cs.get(i) + " failed: " + e.getMessage());
                }
                final long ms = Math.max(1, SystemClock.elapsedRealtime() - s0);
                final String first = out.isEmpty() ? "" : out.get(0);
                final String mode = (first.startsWith("?") || first.startsWith("✗")) ? "fail" : "done";
                ui.post(() -> {
                    if (seq != runSeq) return;
                    acc[0] += ms;
                    stepState(row, mode, first + "  ·  " + ms + " ms");
                    if (summary.length() > 0) summary.append("; ");
                    summary.append(first.replaceAll("^[▸✗?] ", ""));
                    ui.postDelayed(next[0], 240);
                });
            }), 220);
        };
        ui.postDelayed(next[0], 140);
    }

    void finishRun(long totalMs, String cmd, String res, long t0, int seq) {
        long wall = Math.max(totalMs, SystemClock.elapsedRealtime() - t0);
        String status = res.contains("? ") && res.contains("✗") ? "PARTIAL"
                : res.contains("? ") ? "NOMATCH"
                : res.contains("✗") ? "PARTIAL" : "DONE";
        runMeta.setText("completed in " + wall + " ms");
        switch (status) {
            case "DONE":    setStatusChip("complete", CYAN, false); break;
            case "PARTIAL": setStatusChip("partial", AMBER, false); break;
            default:        setStatusChip("no match", RED, false); break;
        }
        saveRun(System.currentTimeMillis(), cmd, res, status);
        saveChat(cmd, res);
        // sleek exit: let the success state breathe, then tuck the sheet away
        if ("DONE".equals(status) && seq == runSeq) {
            ui.postDelayed(() -> {
                if (seq == runSeq && backdrop.getVisibility() == View.VISIBLE) closeSheet();
                bringToConsole();
            }, 1500);
        }
    }

    // ================================================================== history ==
    JSONArray loadHistory() {
        try { return new JSONArray(sp.getString("history", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    void saveRun(long t, String cmd, String res, String st) {
        try {
            JSONArray h = loadHistory();
            JSONObject o = new JSONObject();
            o.put("t", t); o.put("cmd", cmd); o.put("res", res); o.put("st", st);
            JSONArray nh = new JSONArray();
            nh.put(o);
            for (int i = 0; i < h.length() && i < 49; i++) nh.put(h.get(i));
            sp.edit().putString("history", nh.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ================================================================== answers ==
    void saveAnswer(String q, String a) {
        try {
            JSONArray arr = loadAnswers();
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("q", q);
            o.put("a", a);
            JSONArray na = new JSONArray();
            na.put(o);
            for (int i = 0; i < arr.length() && i < 19; i++) na.put(arr.get(i));
            sp.edit().putString("answers", na.toString()).apply();
            refreshAnswers();
        } catch (Exception ignored) { }
    }

    JSONArray loadAnswers() {
        try { return new JSONArray(sp.getString("answers", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    void refreshAnswers() {
        if (answersBox == null) return;
        answersBox.removeAllViews();
        JSONArray arr = loadAnswers();
        int shown = 0;
        for (int i = 0; i < arr.length() && shown < 3; i++, shown++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                LinearLayout card = col(VERT);
                card.setBackground(box(R_MD, SC_LO, BORDER));
                card.setElevation(dp(1));
                pad(card, 16, 12, 16, 14);
                TextView q = tv("you — " + o.getString("q"), 11, OUTLINE, false, true);
                q.setMaxLines(2);
                card.addView(q);
                TextView a = tv(o.getString("a"), 15, ON_SURF, false, false);
                a.setLineSpacing(dp(2), 1.05f);
                pad(a, 0, 6, 0, 0);
                card.addView(a);
                LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                p.bottomMargin = dp(8);
                answersBox.addView(card, p);
            } catch (Exception ignored) { }
        }
        if (shown == 0) {
            TextView empty = tv("answers from web research land here — try \"go to wikipedia and find out who is Ada Lovelace\"", 12, OUTLINE, false, false);
            empty.setLineSpacing(dp(2), 1.05f);
            answersBox.addView(empty);
        }
    }

    // ==================================================================== chat ==
    void saveChat(String u, String a) {
        try {
            JSONArray arr = loadChat();
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("u", u);
            o.put("a", a);
            JSONArray na = new JSONArray();
            na.put(o);
            for (int i = 0; i < arr.length() && i < 99; i++) na.put(arr.get(i));
            sp.edit().putString("chat", na.toString()).apply();
            chatSavedForRun = true;
            refreshChat();
        } catch (Exception ignored) { }
    }

    JSONArray loadChat() {
        try {
            if (!sp.getBoolean("chat_seed_v2", false)) {
                seedChatFromHistory();
                sp.edit().putBoolean("chat_seed_v2", true).apply();
            }
            return new JSONArray(sp.getString("chat", "[]"));
        } catch (Exception e) { return new JSONArray(); }
    }

    void seedChatFromHistory() {
        try {
            JSONArray h = loadHistory();
            JSONArray out = new JSONArray();
            int n = Math.min(10, h.length());
            for (int i = 0; i < n; i++) {
                JSONObject o = h.getJSONObject(i);
                String res = o.optString("res", "");
                if (res.isEmpty()) continue;
                int ri = res.lastIndexOf("read → ");
                if (ri >= 0) res = res.substring(ri + "read → ".length()).trim();
                JSONObject c = new JSONObject();
                c.put("t", o.optLong("t", 0));
                c.put("u", o.optString("cmd", ""));
                c.put("a", res);
                out.put(c);
            }
            sp.edit().putString("chat", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    void refreshChat() {
        if (chatBox == null) return;
        chatBox.removeAllViews();
        JSONArray arr = loadChat();
        if (arr.length() == 0) {
            LinearLayout row = col(HORIZ);
            row.setGravity(Gravity.START);
            TextView b = tv("hey — i run on this phone. ask me to open apps, set things, or find stuff out.\ntry \"check messages\" or \"go to wikipedia and find out who is Ada Lovelace\"",
                    14, ON_SURF, false, false);
            b.setBackground(box(20, SC_LO, BORDER));
            pad(b, 14, 10, 14, 10);
            b.setLineSpacing(dp(2), 1.05f);
            b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
            row.addView(b);
            chatBox.addView(row);
        } else {
            long lastT = 0;
            for (int i = arr.length() - 1; i >= 0; i--) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    long t = o.optLong("t", 0);
                    if (t - lastT > 5 * 60 * 1000) {
                        TextView sep = tv(timeStr(t), 10, OUTLINE, false, true);
                        sep.setGravity(Gravity.CENTER);
                        pad(sep, 0, 12, 0, 6);
                        chatBox.addView(sep, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                    }
                    lastT = t;
                    chatBox.addView(userBubble(o.optString("u", "")));
                    chatBox.addView(agentBubble(o.optString("a", "")));
                } catch (Exception ignored) { }
            }
        }
        if (chatScroll != null) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    View userBubble(String text) {
        LinearLayout row = col(HORIZ);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(6);
        TextView b = tv(text, 15, Color.WHITE, false, false);
        b.setBackground(box(20, INDIGO, 0));
        pad(b, 14, 10, 14, 10);
        b.setLineSpacing(dp(2), 1.05f);
        b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
        row.addView(b);
        row.setLayoutParams(rp);
        return row;
    }

    View agentBubble(String text) {
        LinearLayout row = col(HORIZ);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams rp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(6);
        TextView b = tv(text, 15, ON_SURF, false, false);
        b.setBackground(box(20, SC_LO, BORDER));
        pad(b, 14, 10, 14, 10);
        b.setLineSpacing(dp(2), 1.05f);
        b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
        row.addView(b);
        row.setLayoutParams(rp);
        return row;
    }

    // ==================================================================== voice ==
    // No system dialog — SpeechRecognizer runs inside our popup (Technical Premium sheet).
    void startSpeech() {
        voiceBusyRetries = 0;
        // the wake word recognizer already owns the mic — release it first, then
        // grab the mic once it has actually let go (otherwise ERROR_RECOGNIZER_BUSY)
        boolean wakeWasActive = wakeListening || wakeRecog != null;
        if (wakeWasActive || (sp != null && sp.getBoolean("set_wake", false))) pauseWakeForVoice();
        showVoicePopup();
        if (wakeWasActive) {
            setVoiceStatus("waiting", AMBER, true);
            animateVoiceBars(false);
            voiceHint.setText("releasing wake word…");
            ui.postDelayed(this::ensureMicAndStartVoice, 500);
        } else {
            ensureMicAndStartVoice();
        }
    }

    /** release the mic from the wake recognizer; dismissVoice() resumes it afterwards */
    void pauseWakeForVoice() {
        wakePausedForVoice = true;
        wakeListening = false;
        if (wakeRestart != null) wakeHandler.removeCallbacks(wakeRestart);
        try { if (wakeRecog != null) { wakeRecog.cancel(); wakeRecog.destroy(); wakeRecog = null; } } catch (Exception ignored) {}
        if (wakeDot != null) wakeDot.setBackground(box(999, AMBER, 0));
    }

    void ensureMicAndStartVoice() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingVoiceAction = "start";
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                setVoiceStatus("mic needed", AMBER, false);
                animateVoiceBars(false);
                voiceTranscript.setText("Microphone permission needed");
                voiceTranscript.setTextColor(AMBER);
                voiceHint.setText("tap Allow — then speak");
                return;
            }
        }
        startVoiceListening();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_MIC) {
            boolean ok = grants.length > 0 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if ("start".equals(pendingVoiceAction)) {
                pendingVoiceAction = "";
                if (ok) startVoiceListening();
                else {
                    setVoiceStatus("blocked", RED, false);
                    animateVoiceBars(false);
                    voiceTranscript.setText("! microphone blocked");
                    voiceTranscript.setTextColor(RED);
                    voiceHint.setText("Settings → Apps → Pocket Harness → Permissions → Microphone");
                }
            } else if ("wake".equals(pendingVoiceAction)) {
                pendingVoiceAction = "";
                if (ok) startWake();
                else {
                    // revert toggle
                    sp.edit().putBoolean("set_wake", false).apply();
                    if (wakeDot != null) wakeDot.setBackground(box(999, BORDER, 0));
                }
            } else {
                // mic granted from onboarding — reflect real state
                syncPermFlags();
                if ("onboard".equals(currentScreen)) showScreen("onboard");
            }
        }
    }

    /** reconcile stored grant flags with the real device state */
    void syncPermFlags() {
        if (sp == null) return;
        boolean write = Settings.System.canWrite(this);
        boolean mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        sp.edit().putBoolean("pg_write", write)
                .putBoolean("pg_mic", mic)
                .putBoolean("pg_a11y", HarnessService.isEnabled())
                .apply();
    }

    void startVoiceListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setVoiceStatus("no engine", RED, false);
            animateVoiceBars(false);
            voiceTranscript.setText("! no recognizer on this device");
            voiceTranscript.setTextColor(RED);
            voiceHint.setText("install Google app");
            return;
        }
        try { if (speechRecog != null) { speechRecog.cancel(); speechRecog.destroy(); } } catch (Exception ignored) {}
        speechRecog = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecog.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) {
                voiceListening = true;
                setVoiceStatus("listening", CYAN, true);
                animateVoiceBars(true);
                voiceTranscript.setTextColor(MUTED);
                voiceHint.setText("speak now · auto-sends on silence");
            }
            public void onBeginningOfSpeech() {
                setVoiceStatus("hearing", INDIGO_HI, true);
            }
            public void onRmsChanged(float rms) {
                // drive middle bar subtly with mic level (0..10)
                if (voiceBars != null && voiceBars.getChildCount() >= 3) {
                    float norm = Math.min(1f, Math.max(0f, (rms + 2f) / 10f));
                    View mid = voiceBars.getChildAt(2);
                    ViewGroup.LayoutParams lp2 = mid.getLayoutParams();
                    lp2.height = dp(8) + Math.round(norm * 20);
                    mid.setLayoutParams(lp2);
                }
            }
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() {
                setVoiceStatus("processing", AMBER, true);
                animateVoiceBars(false);
                voiceHint.setText("processing…");
            }
            public void onError(int code) {
                voiceListening = false;
                animateVoiceBars(false);
                // mic momentarily held by the wake listener → wait a beat and retry
                if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && voiceBusyRetries < 2) {
                    voiceBusyRetries++;
                    pauseWakeForVoice();
                    setVoiceStatus("reconnecting", AMBER, true);
                    voiceHint.setText("releasing mic…");
                    ui.postDelayed(MainActivity.this::startVoiceListening, 650);
                    return;
                }
                String msg = "error " + code;
                if (code == SpeechRecognizer.ERROR_AUDIO) msg = "audio error";
                else if (code == SpeechRecognizer.ERROR_CLIENT) msg = "client error";
                else if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) msg = "mic permission";
                else if (code == SpeechRecognizer.ERROR_NETWORK) msg = "network needed for recognizer";
                else if (code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) msg = "network timeout";
                else if (code == SpeechRecognizer.ERROR_NO_MATCH) msg = "didn't catch that";
                else if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) msg = "busy — try again";
                else if (code == SpeechRecognizer.ERROR_SERVER) msg = "server error";
                else if (code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) msg = "no speech heard";
                setVoiceStatus(msg, code == SpeechRecognizer.ERROR_NO_MATCH ? OUTLINE : RED, false);
                voiceTranscript.setTextColor(code == SpeechRecognizer.ERROR_NO_MATCH ? OUTLINE : RED);
                if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    voiceTranscript.setText("try again — e.g. “open youtube”");
                    voiceHint.setText("tap Cancel or try again");
                    ui.postDelayed(() -> { if (voiceBackdrop.getVisibility()==View.VISIBLE) dismissVoice(); }, 1600);
                } else {
                    voiceTranscript.setText("! " + msg);
                    voiceHint.setText("tap outside to retry");
                }
            }
            public void onResults(Bundle r) {
                voiceListening = false;
                animateVoiceBars(false);
                ArrayList<String> got = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (got != null && !got.isEmpty()) {
                    String heard = got.get(0).trim();
                    setVoiceStatus("heard", CYAN, false);
                    voiceTranscript.setText("“" + heard + "”");
                    voiceTranscript.setTextColor(ON_SURF);
                    voiceHint.setText("sending to harness…");
                    ui.postDelayed(() -> {
                        dismissVoice();
                        ui.postDelayed(() -> { if (cmdInput != null) { cmdInput.setText(heard); runCommand(); } }, 220);
                    }, 420);
                } else onError(SpeechRecognizer.ERROR_NO_MATCH);
            }
            public void onPartialResults(Bundle p) {
                ArrayList<String> got = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (got != null && !got.isEmpty()) {
                    String part = got.get(0).trim();
                    if (!part.isEmpty()) {
                        voiceTranscript.setText("“" + part + "”");
                        voiceTranscript.setTextColor(ON_SURF);
                    }
                }
            }
            public void onEvent(int e, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try { speechRecog.startListening(i); }
        catch (Exception e) {
            setVoiceStatus("failed", RED, false);
            voiceTranscript.setText("! " + e.getMessage());
            voiceTranscript.setTextColor(RED);
        }
    }

    void stopVoiceListening() {
        voiceListening = false;
        try { if (speechRecog != null) speechRecog.stopListening(); } catch (Exception ignored) {}
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        // kept for legacy RecognizerIntent fallback (not used now — SpeechRecognizer is primary)
        if (req != REQ_SPEECH) return;
        if (res == RESULT_OK && data != null) {
            ArrayList<String> got = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (got != null && !got.isEmpty()) {
                String heard = got.get(0);
                setVoiceStatus("heard", CYAN, false);
                animateVoiceBars(false);
                voiceTranscript.setText("“" + heard + "”");
                voiceTranscript.setTextColor(ON_SURF);
                voiceHint.setText("sending to harness…");
                ui.postDelayed(() -> {
                    dismissVoice();
                    ui.postDelayed(() -> { if (cmdInput != null) { cmdInput.setText(heard); runCommand(); } }, 220);
                }, 420);
                return;
            }
        }
        if (voiceBackdrop != null && voiceBackdrop.getVisibility() == View.VISIBLE) {
            setVoiceStatus("cancelled", OUTLINE, false);
            animateVoiceBars(false);
            ui.postDelayed(() -> dismissVoice(), 500);
        }
    }

    @Override public void onBackPressed() {
        if (voiceBackdrop != null && voiceBackdrop.getVisibility() == View.VISIBLE) { dismissVoice(); return; }
        if (backdrop != null && backdrop.getVisibility() == View.VISIBLE) { closeSheet(); return; }
        if (!"home".equals(currentScreen) && !"onboard".equals(currentScreen)
                && sp.getBoolean("ob_done", false)) { showScreen("home"); return; }
        super.onBackPressed();
    }
    // keep deprecated override for API 24 compat; no OnBackPressedDispatcher needed here

    // ====================================================================== orb ==
    /** radial-gradient orb with double expanding rings — mockups.html:131 .ring ×2 */
    class Orb extends View {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float phase = 0f;
        ValueAnimator anim;

        Orb(Context c) {
            super(c);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(1));
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(2600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        void drawRing(Canvas c, float cx, float cy, float r, float ph) {
            if (ph <= 0.05f) return;
            float p = ph > 1f ? ph - 1f : ph;
            int alpha = (int) (130 * (1 - p));
            ringPaint.setColor(Color.argb(alpha, 0x63, 0x66, 0xF1));
            c.drawCircle(cx, cy, r * (1f + 0.45f * p), ringPaint);
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = getWidth() / 2f;
            paint.setShader(new RadialGradient(cx, cy * 0.7f, r,
                    new int[]{0xFF26262B, 0xFF101013}, new float[]{0f, 0.85f},
                    Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r - dp(1), paint);
            paint.setShader(null);
            drawRing(c, cx, cy, r, phase);
            float ph2 = phase + 0.5f; if (ph2 > 1f) ph2 -= 1f;
            drawRing(c, cx, cy, r, ph2);
        }

        @Override protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            super.onDetachedFromWindow();
        }
    }

    // =================================================================== toggle ==
    /** thin 36×20 switch: zinc track → indigo when on, white thumb */
    class Toggle extends View {
        boolean on;
        final boolean enabled;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(boolean enabled) { super(MainActivity.this); this.enabled = enabled; }

        void setOn(boolean v) { on = v; invalidate(); }
        boolean isOn() { return on; }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(dp(36), dp(20)); }

        @Override protected void onDraw(Canvas c) {
            p.setColor(on ? INDIGO : BORDER);
            c.drawRoundRect(0, 0, getWidth(), getHeight(), getHeight() / 2f, getHeight() / 2f, p);
            p.setColor(Color.WHITE);
            float x = on ? getWidth() - dp(10) : dp(10);
            c.drawCircle(x, getHeight() / 2f, dp(5), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) { return enabled && super.onTouchEvent(e); }
    }
}
