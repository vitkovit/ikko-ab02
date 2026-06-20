package com.mw.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Drives IKKO's own control panel like a human, since the buds only obey IKKO's
 * (system-signed) app. Two jobs:
 *   1. Open IKKO's control panel on demand (launch ui.main.MainActivity, then inject
 *      the left-edge swipe the panel responds to — adb input can't, but an a11y
 *      gesture is delivered into the app like a real touch).
 *   2. Switch ANC by swiping the native NoiseCancellingView (id "ncv").
 *   3. Scrape the real L/R/case battery from the panel's text nodes into prefs so the
 *      launcher can show them.
 */
public class AncAccessibilityService extends AccessibilityService {

    static final String IKKO = "com.ikkoaudio.launcher";
    static final String IKKO_MAIN = "com.ikkoaudio.launcher.ui.main.MainActivity";

    static volatile AncAccessibilityService instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private int sw = 368, sh = 448;

    @Override
    protected void onServiceConnected() {
        instance = this;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (dm.widthPixels > 0) { sw = dm.widthPixels; sh = dm.heightPixels; }
    }

    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public void onInterrupt() {}

    /** Open the Recents/overview so the user can swipe apps away. */
    public boolean openRecents() {
        try { return performGlobalAction(GLOBAL_ACTION_RECENTS); }
        catch (Throwable t) { return false; }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null && IKKO.contentEquals(pkg)) scrapeBattery();
    }

    // ---- Battery scraping -----------------------------------------------------

    private void scrapeBattery() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String box = textOf(root, "tv_box_battery");
            String l = textOf(root, "tv_left_earbuds_battery");
            String r = textOf(root, "tv_right_earbuds_battery");
            if (box != null || l != null || r != null) {
                SharedPreferences.Editor ed = getSharedPreferences("launcher", MODE_PRIVATE).edit();
                if (box != null) ed.putString("batt_case", box);
                if (l != null) ed.putString("batt_l", l);
                if (r != null) ed.putString("batt_r", r);
                ed.apply();
            }
        } finally {
            root.recycle();
        }
    }

    private String textOf(AccessibilityNodeInfo root, String idName) {
        List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(IKKO + ":id/" + idName);
        if (ns == null || ns.isEmpty()) return null;
        CharSequence t = ns.get(0).getText();
        return t == null ? null : t.toString();
    }

    // ---- Actions --------------------------------------------------------------

    /** Open IKKO's control panel (launch its activity, then swipe to the panel). */
    public void openControlPanel() {
        Log.i("AncAcc", "openControlPanel");
        launchIkko();
        h.postDelayed(this::swipeToPanel, 1300);
    }

    /** Advance the native ANC wheel one mode, then return to our launcher. */
    public void cycleAnc() {
        Log.i("AncAcc", "cycleAnc");
        launchIkko();
        h.postDelayed(this::swipeToPanel, 1300);
        h.postDelayed(this::swipeNcv, 2200);
        h.postDelayed(this::goHome, 3300);
    }

    private void launchIkko() {
        Intent i = new Intent();
        i.setClassName(IKKO, IKKO_MAIN);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home); } catch (Exception ignored) {}
    }

    /** The control panel is the page reached by swiping in from the left edge. */
    private void swipeToPanel() {
        Path p = new Path();
        float y = sh * 0.5f;
        p.moveTo(2, y);
        p.lineTo(sw * 0.88f, y);
        gesture(p, 280);
    }

    /** ncv (the ANC wheel) is the right-edge strip; swipe up = next mode. */
    private void swipeNcv() {
        Path p = new Path();
        float x = sw * 0.81f;       // centre of the ncv strip (~x298 on 368px)
        p.moveTo(x, sh * 0.62f);
        p.lineTo(x, sh * 0.30f);
        gesture(p, 240);
    }

    private void gesture(Path p, int durationMs) {
        try {
            boolean ok = dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs))
                    .build(), new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription g) { Log.i("AncAcc", "gesture completed"); }
                        @Override public void onCancelled(GestureDescription g) { Log.i("AncAcc", "gesture CANCELLED"); }
                    }, null);
            Log.i("AncAcc", "dispatchGesture returned " + ok);
        } catch (Exception e) { Log.w("AncAcc", "gesture err", e); }
    }
}
