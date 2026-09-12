package com.pocketharness;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * v0.2 AccessibilityService — taps + screenshots for vision loop.
 * User must enable in Settings → Accessibility → Pocket Harness.
 */
public class HarnessService extends AccessibilityService {

    private static HarnessService instance;

    @Override public void onServiceConnected() {
        instance = this;
        Log.i("PH_A11Y", "service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    public static boolean isEnabled() { return instance != null; }

    // ---- taps ----
    public static boolean tap(int x, int y) {
        if (instance == null) {
            Log.w("PH_A11Y", "tap failed — service not enabled");
            return false;
        }
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 80));
        try {
            boolean ok = instance.dispatchGesture(b.build(), null, null);
            Log.i("PH_A11Y", "tap " + x + "," + y + " -> " + ok);
            return ok;
        } catch (Throwable e) {
            Log.w("PH_A11Y", "tap failed: " + e);
            return false;
        }
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        if (instance == null) return false;
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, durationMs)));
        try {
            return instance.dispatchGesture(b.build(), null, null);
        } catch (Throwable e) {
            Log.w("PH_A11Y", "swipe failed: " + e);
            return false;
        }
    }

    /** set text on the focused editable via the accessibility node (no shell). */
    public static boolean inputText(String text) {
        if (instance == null) {
            Log.w("PH_A11Y", "input failed — service not enabled");
            return false;
        }
        try {
            AccessibilityNodeInfo node = instance.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (node == null) node = findEditable(instance.getRootInActiveWindow());
            if (node == null) {
                Log.w("PH_A11Y", "input failed — no editable field focused");
                return false;
            }
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            node.recycle();
            Log.i("PH_A11Y", "input text via a11y: " + ok);
            return ok;
        } catch (Throwable e) {
            Log.w("PH_A11Y", "input failed: " + e);
            return false;
        }
    }

    public static boolean globalBack() {
        if (instance == null) return false;
        try { return instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); }
        catch (Throwable e) { return false; }
    }

    public static boolean globalHome() {
        if (instance == null) return false;
        try { return instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }
        catch (Throwable e) { return false; }
    }

    /** press the IME enter/action key on the focused field (API 30+). */
    public static boolean imeEnter() {
        if (instance == null || Build.VERSION.SDK_INT < 30) return false;
        try {
            AccessibilityNodeInfo node = instance.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (node == null) return false;
            boolean ok = node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            node.recycle();
            return ok;
        } catch (Throwable e) {
            return false;
        }
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findEditable(child);
            if (found != null) return found;
            child.recycle();
        }
        return null;
    }

    // ---- set-of-mark: actionable node dump ----
    public static class Node {
        public int x1, y1, x2, y2;          // screen pixels
        public String cls = "";
        public String label = "";
        public boolean clickable, editable, scrollable;
    }

    private static final List<Node> lastNodes = new ArrayList<>();

    public static synchronized List<Node> lastNodes() {
        return new ArrayList<>(lastNodes);
    }

    private static void collect(AccessibilityNodeInfo n, List<Node> out, int depth) {
        if (n == null || depth > 30 || out.size() >= 80) return;
        CharSequence pkg = n.getPackageName();
        if (pkg != null && "com.pocketharness".contentEquals(pkg)) return;
        if (n.isVisibleToUser()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            boolean actionable = n.isClickable() || n.isEditable()
                    || n.isScrollable() || n.isLongClickable();
            if (actionable && r.width() >= 8 && r.height() >= 8 && r.right > 0 && r.bottom > 0) {
                CharSequence text = n.getText();
                CharSequence desc = n.getContentDescription();
                String label = text != null && text.length() > 0 ? text.toString()
                        : desc != null ? desc.toString() : "";
                label = label.replaceAll("\\s+", " ").trim();
                if (label.length() > 64) label = label.substring(0, 64);
                String lc = label.toLowerCase(java.util.Locale.US);
                boolean noise = lc.equals("install") || lc.startsWith("install ")
                        || lc.equals("update") || lc.startsWith("update ")
                        || lc.equals("get app") || lc.equals("open in app")
                        || lc.equals("subscribe");
                if (!noise) {
                    Node nd = new Node();
                    nd.x1 = r.left; nd.y1 = r.top; nd.x2 = r.right; nd.y2 = r.bottom;
                    nd.cls = String.valueOf(n.getClassName())
                            .replace("android.widget.", "").replace("android.view.", "");
                    nd.label = label;
                    nd.clickable = n.isClickable();
                    nd.editable = n.isEditable();
                    nd.scrollable = n.isScrollable();
                    out.add(nd);
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    /** snapshot of visible actionable elements, top-to-bottom; ids are 1-based */
    public static synchronized List<Node> dumpNodes() {
        List<Node> out = new ArrayList<>();
        if (instance != null) {
            try { collect(instance.getRootInActiveWindow(), out, 0); }
            catch (Throwable e) { Log.w("PH_A11Y", "dump failed: " + e); }
        }
        List<Node> dedup = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (Node n : out) {
            String key = n.x1 + "," + n.y1 + "," + n.x2 + "," + n.y2 + "|" + n.label;
            if (seen.add(key)) dedup.add(n);
        }
        Collections.sort(dedup, (a, b) ->
                a.y1 != b.y1 ? Integer.compare(a.y1, b.y1) : Integer.compare(a.x1, b.x1));
        if (dedup.size() > 40) dedup = new ArrayList<>(dedup.subList(0, 40));
        lastNodes.clear();
        lastNodes.addAll(dedup);
        return dedup;
    }

    /** click element #idx from the most recent dump — exact bounds, no model coords */
    public static boolean clickIndex(int idx) {
        List<Node> nodes;
        synchronized (HarnessService.class) {
            nodes = new ArrayList<>(lastNodes);
        }
        if (idx < 1 || idx > nodes.size()) return false;
        Node n = nodes.get(idx - 1);
        Log.i("PH_A11Y", "click #" + idx + " · " + n.cls + " \"" + n.label + "\"");
        return tap((n.x1 + n.x2) / 2, (n.y1 + n.y2) / 2);
    }

    /** all visible text on screen, newline-joined — for research / answers */
    public static String pageText() {
        if (instance == null) return "";
        StringBuilder sb = new StringBuilder();
        try { collectText(instance.getRootInActiveWindow(), sb, 0); }
        catch (Throwable e) { Log.w("PH_A11Y", "pageText failed: " + e); }
        String[] lines = sb.toString().split("\n");
        StringBuilder out = new StringBuilder();
        String prev = null;
        for (String ln : lines) {
            String t = ln.trim();
            if (t.isEmpty() || t.equals(prev)) continue;
            out.append(t).append("\n");
            prev = t;
            if (out.length() > 8000) break;
        }
        return out.toString();
    }

    private static void collectText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40 || sb.length() > 12000) return;
        CharSequence pkg = n.getPackageName();
        if (pkg != null && "com.pocketharness".contentEquals(pkg)) return;
        if (n.isVisibleToUser()) {
            CharSequence t = n.getText();
            if (t == null || t.length() == 0) t = n.getContentDescription();
            if (t != null && t.length() > 1) sb.append(t.toString()).append("\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) collectText(n.getChild(i), sb, depth + 1);
    }

    /** draw numbered boxes over the screenshot for set-of-mark prompting */
    private static Bitmap annotate(Bitmap src, List<Node> nodes) {
        int w = src.getWidth(), h = src.getHeight();
        float scale = w > 720 ? 720f / w : 1f;
        int ow = Math.round(w * scale), oh = Math.round(h * scale);
        Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, null, new Rect(0, 0, ow, oh), null);

        Paint fill = new Paint();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x2200E5FF);
        Paint box = new Paint();
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(3f);
        box.setColor(0xFF00E5FF);
        Paint badge = new Paint();
        badge.setStyle(Paint.Style.FILL);
        badge.setColor(0xE6000000);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xFFFFFFFF);
        txt.setTextSize(Math.max(20f, ow / 36f));
        txt.setFakeBoldText(true);

        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            RectF r = new RectF(n.x1 * scale, n.y1 * scale, n.x2 * scale, n.y2 * scale);
            c.drawRect(r, fill);
            c.drawRect(r, box);
            String tag = String.valueOf(i + 1);
            float tw = txt.measureText(tag);
            float th = txt.getTextSize();
            float by = Math.max(r.top, th + 4);
            RectF bd = new RectF(r.left, by - th - 4, r.left + tw + 12, by);
            c.drawRect(bd, badge);
            c.drawText(tag, bd.left + 6, bd.bottom - 5, txt);
        }
        return out;
    }

    // ---- screenshot ----
    public interface ShotCb { void onShot(String base64, int w, int h); void onError(String msg); }
    public interface MarkedCb { void onShot(String base64, int w, int h, List<Node> nodes); void onError(String msg); }
    private interface BitmapCb { void onBitmap(Bitmap b); void onError(String msg); }

    public static void screenshotBase64(final ShotCb cb) {
        capture(new BitmapCb() {
            @Override public void onBitmap(Bitmap b) {
                String b64 = toBase64(b, 720);
                cb.onShot(b64, b.getWidth(), b.getHeight());
                b.recycle();
            }
            @Override public void onError(String msg) { cb.onError(msg); }
        });
    }

    /** screenshot with numbered boxes over every actionable element */
    public static void screenshotMarked(final MarkedCb cb) {
        capture(new BitmapCb() {
            @Override public void onBitmap(Bitmap b) {
                try {
                    List<Node> nodes = dumpNodes();
                    Bitmap marked = annotate(b, nodes);
                    String b64 = toBase64(marked, 720);
                    Log.i("PH_A11Y", "marked " + marked.getWidth() + "x" + marked.getHeight()
                            + " nodes " + nodes.size());
                    cb.onShot(b64, b.getWidth(), b.getHeight(), nodes);
                    marked.recycle();
                } catch (Throwable e) {
                    cb.onError("annotate failed: " + e);
                } finally {
                    b.recycle();
                }
            }
            @Override public void onError(String msg) { cb.onError(msg); }
        });
    }

    private static void capture(final BitmapCb cb) {
        if (instance == null) {
            cb.onError("AccessibilityService not enabled — grant in Settings → Accessibility → Pocket Harness");
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                instance.takeScreenshot(0, Runnable::run, new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        Bitmap bmp = null;
                        try {
                            bmp = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                            if (bmp == null) { cb.onError("wrapHardwareBuffer null"); return; }
                            Bitmap copy = bmp.copy(Bitmap.Config.ARGB_8888, false);
                            if (copy == null) { cb.onError("bitmap copy failed"); return; }
                            Log.i("PH_A11Y", "screenshot " + copy.getWidth() + "x" + copy.getHeight());
                            cb.onBitmap(copy);
                        } catch (Throwable e) {
                            cb.onError("screenshot failed: " + e);
                        } finally {
                            if (bmp != null) bmp.recycle();
                            result.getHardwareBuffer().close();
                        }
                    }
                    @Override public void onFailure(int err) {
                        String m = "takeScreenshot failed";
                        if (err == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
                            m = "screenshots too fast — retry";
                        else if (err == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)
                            m = "no screenshot access — re-enable accessibility";
                        else if (err == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY)
                            m = "invalid display";
                        Log.w("PH_A11Y", m + " (code " + err + ")");
                        cb.onError(m + " (code " + err + ")");
                    }
                });
            } catch (Exception e) { cb.onError("takeScreenshot exception " + e); }
        } else {
            // API 24-29 fallback: shell screencap (works only on builds that still allow
            // apps to exec /system/bin/screencap; usually fails with a clear error).
            new Thread(() -> {
                try {
                    Process p = Runtime.getRuntime().exec("screencap -p");
                    java.io.InputStream is = p.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    p.waitFor();
                    byte[] png = baos.toByteArray();
                    if (png.length < 100) { cb.onError("screencap empty " + png.length); return; }
                    Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (bmp == null) { cb.onError("decode png failed " + png.length); return; }
                    cb.onBitmap(bmp);
                } catch (Exception e) { cb.onError("shell screencap failed: " + e); }
            }).start();
        }
    }

    private static String toBase64(Bitmap bmp, int maxW) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        Bitmap src = bmp;
        if (w > maxW) {
            float s = maxW / (float) w;
            src = Bitmap.createScaledBitmap(bmp, maxW, Math.round(h * s), true);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        src.compress(Bitmap.CompressFormat.JPEG, 72, baos);
        if (src != bmp) src.recycle();
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }
}
