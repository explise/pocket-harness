package com.pocketharness;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;

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

    // ---- screenshot -> base64 ----
    public interface ShotCb { void onShot(String base64, int w, int h); void onError(String msg); }

    public static void screenshotBase64(ShotCb cb) {
        if (instance == null) {
            cb.onError("AccessibilityService not enabled — grant in Settings → Accessibility → Pocket Harness");
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                instance.takeScreenshot(0, Runnable::run, new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        Bitmap bmp = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                        try {
                            if (bmp == null) { cb.onError("wrapHardwareBuffer null"); return; }
                            Bitmap copy = bmp.copy(Bitmap.Config.ARGB_8888, false);
                            String b64 = toBase64(copy, 720);
                            Log.i("PH_A11Y", "screenshot " + copy.getWidth() + "x" + copy.getHeight() + " b64 " + b64.length());
                            cb.onShot(b64, copy.getWidth(), copy.getHeight());
                            copy.recycle();
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
                        android.util.Log.w("PH_A11Y", m + " (code " + err + ")");
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
                    String b64 = toBase64(bmp, 720);
                    Log.i("PH_A11Y", "shell screencap " + bmp.getWidth() + "x" + bmp.getHeight() + " b64 " + b64.length());
                    cb.onShot(b64, bmp.getWidth(), bmp.getHeight());
                    bmp.recycle();
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
