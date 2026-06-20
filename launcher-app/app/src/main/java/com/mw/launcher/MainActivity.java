package com.mw.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.os.Build;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Nothing-styled watch-face home launcher for the IKKO Activebuds AB02
 * (physically ~1.5", 368x448, nominal 160 dpi — sized up to read in the hand).
 *
 *   HOME: Ndot57 dot-matrix clock + date, a 5-bar Nothing battery gauge, an
 *         "all apps" chip TOP-LEFT and a monochrome Settings chip TOP-RIGHT (both
 *         inset to clear the curvature), and a centered bottom bar of pinned apps.
 *   ALL APPS: a grid of every app — tap to add/remove from the bar (up to 4).
 */
public class MainActivity extends Activity implements ControlWidget.Host {

    private static final String PREFS = "launcher";
    private static final String KEY_PINS = "pins";
    private static final String KEY_SEEDED = "seeded";
    private static final String KEY_ANC_MODE = "anc_mode";
    private static final String SELF_PKG = "com.mw.launcher";

    private static final int MAX_BAR     = 4;
    private static final int CLOCK_SP    = 108;
    private static final int CLOCK_MAXW_CLEAR = 266;  // clock max width when no alerts (grows to fit)
    private static final int CLOCK_MAXW_ALERT = 224;  // shrunk to clear the right-side alert column
    private static final int DATE_SP     = 30;
    private static final int BAR_ICON_DP = 70;
    private static final int DRAWER_ICON_DP = 58;
    private static final int DRAWER_LABEL_SP = 18;
    private static final int DRAWER_COLS = 2;
    private static final int EDGE_INSET  = 18;
    private static final int CHIP_DP     = 58;   // larger corner tap target (rounded-corner reach)
    private static final int BAR_BOTTOM  = 20;
    private static final int STRIP_LEFT_PX = 255; // physical edge-bar reports at screen x 257..341 (pixels)
    private static final int STATS_INTERVAL_MS = 15000; // stats poll cadence (relaxed for battery)

    private float density;
    private SharedPreferences prefs;
    private Typeface clockFont, bodyFont, dotFont;
    private ComponentName settingsCn;

    private ViewFlipper flipper;
    private TextView clock, dateView;
    private MarqueeText earbudInfo, tvCase, tvWifi, tvCell, tvBt;
    private SignalDots wifiSig, cellSig;
    private int cellLevel = 0;
    // dotted missed-call / unread-message alerts under the clock (hidden when their count is 0)
    private LinearLayout alertPhoneGroup, alertSmsGroup;
    private TextView alertPhoneCount, alertSmsCount;
    private int missedCalls, unreadSms;
    private static final int ALERT_COLOR = 0xFFE0564B;   // soft red, draws the eye without shouting
    private final android.os.Handler statsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private BatteryBars batteryBars;
    private LinearLayout bottomBar, drawerContainer;
    private TextView drawerCount;
    private GestureDetector homeGestures;
    private AncController anc;
    private ControlWidget controlWidget;
    private MediaController mediaController;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());

    private int dp(float v) { return (int) (v * density + 0.5f); }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        clockFont = loadFont("offbit.ttf");  // offbit_dotbold — the native IKKO clock font
        bodyFont  = loadFont("body.otf");     // NType82 — Nothing grotesque
        dotFont   = loadFont("ndot57.otf");   // Ndot57 — dotted at small sizes, matches the dialer

        Intent si = getPackageManager().getLaunchIntentForPackage("com.android.settings");
        if (si != null) settingsCn = si.getComponent();

        anc = new AncController();
        initBtProxies();
        maybeSeedDefaults();

        flipper = new ViewFlipper(this);
        flipper.addView(buildHomePage());   // child 0
        flipper.addView(buildDrawerPage());  // child 1
        setContentView(flipper);

        refreshBar();
        updateClock();
    }

    private Typeface loadFont(String asset) {
        try { return Typeface.createFromAsset(getAssets(), asset); }
        catch (Exception e) { return Typeface.DEFAULT; }
    }

    // ---- Home (watch face + bottom bar) --------------------------------------

    private View buildHomePage() {
        HomeRoot root = new HomeRoot(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(EDGE_INSET), dp(EDGE_INSET), dp(EDGE_INSET), dp(BAR_BOTTOM));

        // --- Header row: big date, centred between the two corner chips (battery lives in the stats) ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(CHIP_DP));
        hLp.leftMargin = dp(CHIP_DP);    // clear the app-select chip
        hLp.rightMargin = dp(CHIP_DP);   // clear the settings chip

        dateView = new TextView(this);
        dateView.setTextColor(0xFFE0E0E0);
        dateView.setTypeface(dotFont);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        dateView.setMaxLines(1);
        dateView.setIncludeFontPadding(false);
        dateView.setGravity(Gravity.CENTER);
        header.addView(dateView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(header, hLp);

        // --- Clock up-left + stats panel ---
        LinearLayout clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        clockBlock.setGravity(Gravity.START | Gravity.TOP);

        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(clockFont);
        clock.setGravity(Gravity.START);
        clock.setMaxLines(1);
        clock.setIncludeFontPadding(false);   // trim the dot-font's tall line metrics
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_SP);
        // Clock width is dynamic (see refreshAlerts): full when there are no alerts, shrunk to make room
        // for the right-side alert column when a missed-call / unread badge is showing. Start full.
        clock.setMaxWidth(dp(CLOCK_MAXW_CLEAR));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            clock.setAutoSizeTextTypeUniformWithConfiguration(64, CLOCK_SP, 2, TypedValue.COMPLEX_UNIT_SP);
        }
        LinearLayout.LayoutParams clkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clkLp.bottomMargin = -dp(21);   // drop most of the dot-font descent (tuned for dead-centre)
        // spacer above the clock -> it drops lower / more centred instead of crammed under the header
        View spAbove = new View(this);
        clockBlock.addView(spAbove, new LinearLayout.LayoutParams(0, 0, 1f));

        clockBlock.addView(clock, clkLp);

        View spTop = new View(this);    // gap between the clock and the stats
        clockBlock.addView(spTop, new LinearLayout.LayoutParams(0, 0, 0.6f));

        LinearLayout statsBlock = new LinearLayout(this);
        statsBlock.setOrientation(LinearLayout.VERTICAL);
        clockBlock.addView(statsBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wifiSig = new SignalDots(this);
        cellSig = new SignalDots(this);
        earbudInfo = addStat(statsBlock, 0xFF8AB4F8, R.drawable.ic_buds, null);
        tvCase = addStat(statsBlock, 0xFFD0D0D0, R.drawable.ic_battery, null);
        tvWifi = addStat(statsBlock, 0xFFD0D0D0, R.drawable.ic_wifi, wifiSig);
        tvCell = addStat(statsBlock, 0xFFD0D0D0, R.drawable.ic_sim, cellSig);
        tvBt   = addStat(statsBlock, 0xFFD0D0D0, R.drawable.ic_bt, null);

        View spBot = new View(this);    // small gap so the stats hug the app bar
        clockBlock.addView(spBot, new LinearLayout.LayoutParams(0, 0, 0.25f));

        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        col.addView(clockBlock, cbLp);

        // --- Bottom bar — pinned icons, centred ---
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        col.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // All-apps chip — TOP-LEFT
        ChipButton allApps = new ChipButton(this, ChipButton.GLYPH_APPS);
        FrameLayout.LayoutParams lLp = new FrameLayout.LayoutParams(dp(CHIP_DP), dp(CHIP_DP));
        lLp.gravity = Gravity.TOP | Gravity.START;
        lLp.topMargin = dp(EDGE_INSET);
        lLp.leftMargin = dp(EDGE_INSET);
        allApps.setOnClickListener(v -> { hap(v); showDrawer(); });
        root.addView(allApps, lLp);

        // Settings chip — TOP-RIGHT
        if (settingsCn != null) {
            ChipButton gear = new ChipButton(this, ChipButton.GLYPH_GEAR);
            FrameLayout.LayoutParams rLp = new FrameLayout.LayoutParams(dp(CHIP_DP), dp(CHIP_DP));
            rLp.gravity = Gravity.TOP | Gravity.END;
            rLp.topMargin = dp(EDGE_INSET);
            rLp.rightMargin = dp(EDGE_INSET);
            gear.setOnClickListener(v -> { hap(v); launch(settingsCn); });
            root.addView(gear, rLp);
        }

        // --- Control panel — Volume / Media / Brightness. The rail draws on the right, but the
        // widget spans most of the width so a left-to-right swipe ANYWHERE in the band changes page.
        controlWidget = new ControlWidget(this, dotFont);
        controlWidget.setHost(this);
        FrameLayout.LayoutParams aLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        aLp.gravity = Gravity.TOP;
        aLp.topMargin = dp(72);
        aLp.bottomMargin = dp(96);
        aLp.leftMargin = dp(40);    // wide swipe area; leave the left edge for the system back gesture
        aLp.rightMargin = dp(2);
        root.addView(controlWidget, aLp);

        // --- Alert column: dotted missed-call / unread-message glyphs, stacked vertically to the RIGHT
        // of the clock (number above icon). Added on TOP of controlWidget so taps reach it (tap the phone
        // to clear missed calls; tap the envelope to open messages). Each cell hides when its count is 0.
        LinearLayout alertCol = new LinearLayout(this);
        alertCol.setOrientation(LinearLayout.VERTICAL);
        alertCol.setGravity(Gravity.CENTER_HORIZONTAL);
        alertPhoneGroup = buildAlertCell(R.drawable.ic_dot_phone, this::clearMissed);
        alertSmsGroup   = buildAlertCell(R.drawable.ic_dot_envelope, () -> openAlert(false));
        alertPhoneCount = (TextView) alertPhoneGroup.getTag();
        alertSmsCount   = (TextView) alertSmsGroup.getTag();
        alertCol.addView(alertPhoneGroup);
        LinearLayout.LayoutParams smsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        smsLp.topMargin = dp(12);
        alertCol.addView(alertSmsGroup, smsLp);
        FrameLayout.LayoutParams acLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acLp.gravity = Gravity.START | Gravity.TOP;
        acLp.leftMargin = dp(236);   // right of the (width-capped) clock, left of the volume bar
        acLp.topMargin = dp(96);     // sit beside the clock digits, not below them
        root.addView(alertCol, acLp);

        // No custom swipe handling — it fights the device's native gestures (back /
        // notification shade). The app menu is opened from the top-left chip.
        return root;
    }

    private void openIkkoPanel() {
        if (AncAccessibilityService.instance != null) {
            AncAccessibilityService.instance.openControlPanel();
        } else {
            promptEnableAccessibility();
        }
    }

    private void promptEnableAccessibility() {
        Toast.makeText(this, "Turn on “Launcher ANC control” in Accessibility, then try again",
                Toast.LENGTH_LONG).show();
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void updateEarbudInfo() {
        if (earbudInfo == null) return;
        String bt = connectedBtName();
        boolean ikko = bt != null && bt.toLowerCase().contains("activebuds")
                && !bt.toLowerCase().contains("case");
        if (ikko) {                        // buds connected -> show their battery
            String l = prefs.getString("batt_l", null);
            String r = prefs.getString("batt_r", null);
            earbudInfo.setText("L " + (l == null ? "--" : l) + "  R " + (r == null ? "--" : r));
        } else {                           // disconnected -> keep the row, show status
            earbudInfo.setText("not connected");
        }
    }

    private TextView drawerChip(String text, int bgColor) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(dotFont);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(12), dp(6), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(14));
        t.setBackground(bg);
        return t;
    }

    private LinearLayout.LayoutParams chipLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = leftMargin;
        return lp;
    }

    /** Row = [icon] [optional dotted signal staircase] [marquee name]. Name marquees only if it overflows. */
    private MarqueeText addStat(LinearLayout parent, int color, int iconRes, SignalDots sig) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        if (sig != null) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.leftMargin = dp(7);
            row.addView(sig, slp);
        }

        MarqueeText name = new MarqueeText(this);
        name.init(dotFont, getResources().getDisplayMetrics().scaledDensity * 23f, color);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(9);
        row.addView(name, nlp);

        // row is width-bounded so the name field ends well before the right-edge control rail
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels - dp(92),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(7);
        parent.addView(row, rlp);
        return name;
    }

    /** Dotted signal staircase: 4 columns of increasing height; bright up to `level`, dim above. */
    private class SignalDots extends View {
        private int level = -1;
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float pitch, r;
        SignalDots(Context c) {
            super(c);
            float d = getResources().getDisplayMetrics().density;
            pitch = d * 3.4f; r = d * 1.25f;
            on.setColor(0xFFFFFFFF); on.setStyle(Paint.Style.FILL);
            off.setColor(0xFF404040); off.setStyle(Paint.Style.FILL);
        }
        void setLevel(int l) { if (l != level) { level = l; invalidate(); } }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int s = Math.round(4 * pitch);
            setMeasuredDimension(s, s);
        }
        @Override protected void onDraw(Canvas cv) {
            for (int c = 0; c < 4; c++) {
                boolean bright = c < level;
                for (int k = 0; k <= c; k++) {
                    float cx = c * pitch + pitch / 2f;
                    float cy = (3 - k) * pitch + pitch / 2f;   // bottom-aligned column
                    cv.drawCircle(cx, cy, r, bright ? on : off);
                }
            }
        }
    }

    /** Single line that pauses at the start, slow-scrolls to reveal the end, resets, repeats — only if it overflows. */
    private class MarqueeText extends View {
        private String text = "";
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private float textW, scrollX;
        private int phase;                 // 0=pause start, 1=scroll, 2=pause end, 3=fast rewind
        private long phaseStart, lastFrame;
        private float speed;
        private boolean paused;            // battery: frozen while the screen is off / view not visible
        private static final long PAUSE_START = 3000, PAUSE_END = 1000;
        private static final float REWIND_MULT = 6f;   // rewind is this much faster than the forward scroll

        MarqueeText(Context c) { super(c); }
        void init(Typeface f, float textPx, int color) {
            tp.setTypeface(f); tp.setTextSize(textPx); tp.setColor(color);
            speed = getResources().getDisplayMetrics().density * 30f;   // px/sec, slow
        }
        void setText(String s) {
            if (s == null) s = "";
            if (s.equals(text)) return;
            text = s;
            textW = tp.measureText(text);
            scrollX = 0; phase = 0; phaseStart = SystemClock.uptimeMillis();
            removeCallbacks(ticker); if (!paused) post(ticker);
            invalidate();
        }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            Paint.FontMetrics fm = tp.getFontMetrics();
            int h = Math.round(fm.descent - fm.ascent);
            setMeasuredDimension(MeasureSpec.getSize(wSpec), resolveSize(h, hSpec));
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            removeCallbacks(ticker); if (!paused) post(ticker);
        }
        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (paused) return;
                int w = getWidth();
                long now = SystemClock.uptimeMillis();
                if (w <= 0) { postDelayed(this, 16); return; }
                float over = textW - w;
                if (over <= 0) { if (scrollX != 0f) { scrollX = 0f; invalidate(); } return; }  // fits -> static
                switch (phase) {
                    case 0:
                        if (now - phaseStart >= PAUSE_START) { phase = 1; lastFrame = now; }
                        break;
                    case 1:
                        float dt = (now - lastFrame) / 1000f; lastFrame = now;
                        if (dt > 0.05f) dt = 0.05f;
                        scrollX -= speed * dt;
                        if (scrollX <= -over) { scrollX = -over; phase = 2; phaseStart = now; }
                        invalidate();
                        break;
                    case 2:   // hold at the end, then rewind
                        if (now - phaseStart >= PAUSE_END) { phase = 3; lastFrame = now; }
                        break;
                    case 3:   // fast rewind back to the start, then pause
                        float rdt = (now - lastFrame) / 1000f; lastFrame = now;
                        if (rdt > 0.05f) rdt = 0.05f;
                        scrollX += speed * REWIND_MULT * rdt;
                        if (scrollX >= 0f) { scrollX = 0f; phase = 0; phaseStart = now; }
                        invalidate();
                        break;
                }
                postDelayed(this, 16);
            }
        };
        @Override protected void onDraw(Canvas cv) {
            Paint.FontMetrics fm = tp.getFontMetrics();
            float baseline = (getHeight() - (fm.ascent + fm.descent)) / 2f;
            cv.save();
            cv.clipRect(0, 0, getWidth(), getHeight());
            cv.drawText(text, scrollX, baseline, tp);
            cv.restore();
        }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(ticker); }
        @Override protected void onWindowVisibilityChanged(int vis) {
            super.onWindowVisibilityChanged(vis);
            if (vis == VISIBLE) resumeTicker(); else pauseTicker();
        }
        void pauseTicker() { paused = true; removeCallbacks(ticker); }
        void resumeTicker() { paused = false; removeCallbacks(ticker); post(ticker); }  // self-stops if it fits
    }

    private final Runnable statsTick = new Runnable() {
        @Override public void run() {
            updateStats();
            statsHandler.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    private void updateStats() {
        updateEarbudInfo();
        // Case battery is event-driven now (see batteryReceiver -> updateCaseBattery), not polled here.
        // WiFi
        if (tvWifi != null) {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null && wm.isWifiEnabled()) {
                    android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                    int level = android.net.wifi.WifiManager.calculateSignalLevel(wi.getRssi(), 4);
                    String ssid = wi.getSSID() == null ? "" : wi.getSSID().replace("\"", "");
                    if (ssid.isEmpty() || ssid.toLowerCase().contains("unknown")) ssid = "WiFi";
                    tvWifi.setText(ssid); wifiSig.setLevel(level);
                } else { tvWifi.setText("off"); wifiSig.setLevel(0); }
            } catch (Exception e) { tvWifi.setText("--"); wifiSig.setLevel(0); }
        }
        // Cellular
        if (tvCell != null) {
            try {
                android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                        getSystemService(TELEPHONY_SERVICE);
                String op = tm.getNetworkOperatorName();
                if (op == null || op.isEmpty()) op = "No SIM";
                tvCell.setText(op); cellSig.setLevel(cellLevel);
            } catch (Exception e) { tvCell.setText("--"); cellSig.setLevel(0); }
        }
        // Bluetooth — whatever audio device is actually connected (any brand)
        if (tvBt != null) {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            String name = connectedBtName();
            if (name != null) tvBt.setText(name);
            else if (ad == null || !ad.isEnabled()) tvBt.setText("off");   // radio off
            else tvBt.setText("none");                                      // on, nothing connected
        }
        // alerts are event-driven via ContentObserver (see registerAlertObserver) — not polled here
    }

    /** Update the case battery stat row from a battery intent (called by batteryReceiver). */
    private void updateCaseBattery(Intent b) {
        if (tvCase == null || b == null) return;
        int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scl = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        int pct = scl > 0 ? lvl * 100 / scl : lvl;
        tvCase.setText(pct + "%" + (chg ? "  CHG" : ""));
    }

    /** Build a vertical [count] over [dotted icon] tap cell; the count TextView is stashed as the tag. */
    private LinearLayout buildAlertCell(int iconRes, Runnable onTap) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView tv = new TextView(this);
        tv.setTypeface(dotFont);
        tv.setTextColor(ALERT_COLOR);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER);
        g.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(ALERT_COLOR);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        iLp.topMargin = dp(1);
        g.addView(ic, iLp);
        g.setTag(tv);
        g.setVisibility(View.GONE);
        // generous touch target on the small glyph
        g.setPadding(dp(8), dp(4), dp(8), dp(4));
        g.setOnClickListener(v -> { hap(v); onTap.run(); });
        return g;
    }

    /** Tap the phone glyph -> mark all unacknowledged missed calls as seen, clearing the badge. */
    private void clearMissed() {
        try {
            ContentValues v = new ContentValues();
            v.put(CallLog.Calls.NEW, 0);
            v.put(CallLog.Calls.IS_READ, 1);
            getContentResolver().update(CallLog.Calls.CONTENT_URI, v,
                    CallLog.Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE + " AND " + CallLog.Calls.NEW + "=1", null);
        } catch (Exception ignored) {}
        refreshAlerts();
    }

    /** Tap the envelope -> open the messaging app so the unread texts can be read. */
    private void openAlert(boolean phone) {
        try {
            Intent i = phone
                    ? new Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI)
                    : new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    /** Poll missed-call (CallLog NEW+MISSED) and unread-SMS counts; show/hide the dotted glyphs. */
    private void refreshAlerts() {
        missedCalls = countQuery(CallLog.Calls.CONTENT_URI,
                CallLog.Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE + " AND " + CallLog.Calls.NEW + "=1");
        unreadSms = countQuery(Uri.parse("content://sms/inbox"), "read=0");
        if (alertPhoneGroup != null) {
            alertPhoneCount.setText(String.valueOf(missedCalls));
            alertPhoneGroup.setVisibility(missedCalls > 0 ? View.VISIBLE : View.GONE);
        }
        if (alertSmsGroup != null) {
            alertSmsCount.setText(String.valueOf(unreadSms));
            alertSmsGroup.setVisibility(unreadSms > 0 ? View.VISIBLE : View.GONE);
        }
        // grow the clock when nothing is pending; shrink it to make room for the alert column otherwise
        if (clock != null) {
            int want = dp((missedCalls > 0 || unreadSms > 0) ? CLOCK_MAXW_ALERT : CLOCK_MAXW_CLEAR);
            if (clock.getMaxWidth() != want) clock.setMaxWidth(want);
        }
    }

    private int countQuery(Uri uri, String selection) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{"_id"}, selection, null, null);
            return c == null ? 0 : c.getCount();
        } catch (Exception e) {
            return 0;   // permission not yet granted / provider unavailable -> treat as none
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
    }

    private android.bluetooth.BluetoothA2dp a2dpProxy;
    private android.bluetooth.BluetoothHeadset headsetProxy;

    private void initBtProxies() {
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return;
            android.bluetooth.BluetoothProfile.ServiceListener l = new android.bluetooth.BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                    if (profile == android.bluetooth.BluetoothProfile.A2DP) a2dpProxy = (android.bluetooth.BluetoothA2dp) proxy;
                    else if (profile == android.bluetooth.BluetoothProfile.HEADSET) headsetProxy = (android.bluetooth.BluetoothHeadset) proxy;
                    updateStats();
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile == android.bluetooth.BluetoothProfile.A2DP) a2dpProxy = null;
                    else if (profile == android.bluetooth.BluetoothProfile.HEADSET) headsetProxy = null;
                }
            };
            ad.getProfileProxy(this, l, android.bluetooth.BluetoothProfile.A2DP);
            ad.getProfileProxy(this, l, android.bluetooth.BluetoothProfile.HEADSET);
        } catch (Exception ignored) {}
    }

    private String connectedBtName() {
        try {
            if (a2dpProxy != null) {
                java.util.List<android.bluetooth.BluetoothDevice> ds = a2dpProxy.getConnectedDevices();
                if (ds != null && !ds.isEmpty() && ds.get(0).getName() != null) return ds.get(0).getName();
            }
            if (headsetProxy != null) {
                java.util.List<android.bluetooth.BluetoothDevice> ds = headsetProxy.getConnectedDevices();
                if (ds != null && !ds.isEmpty() && ds.get(0).getName() != null) return ds.get(0).getName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private final android.telephony.PhoneStateListener phoneListener = new android.telephony.PhoneStateListener() {
        @Override public void onSignalStrengthsChanged(android.telephony.SignalStrength s) {
            try { cellLevel = s.getLevel(); } catch (Throwable t) {}
            updateStats();
        }
    };

    /** Pinned icons only, centered as a group (1–4 apps). */
    /** Short tactile tick on a button press — fires even if the user has system haptics low. */
    private void hap(View v) {
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignored) {}
    }

    private void refreshBar() {
        if (bottomBar == null) return;
        bottomBar.removeAllViews();
        PackageManager pm = getPackageManager();
        List<ComponentName> pins = loadPins();

        List<ComponentName> alive = new ArrayList<>();
        for (ComponentName cn : pins) {
            if (pm.resolveActivity(launchIntent(cn), 0) != null) alive.add(cn);
        }
        if (alive.size() != pins.size()) savePins(alive);

        for (ComponentName cn : alive) {
            // Dock icon overrides: show a dotted Music / Video glyph for YouTube Music & YouTube,
            // matching the dotted theme — the real apps keep their own icons everywhere else.
            String pkg = cn.getPackageName();
            Drawable icon;
            if (pkg.contains("youtube.music")) {
                icon = getDrawable(R.drawable.ic_fake_music);
            } else if (pkg.contains("youtube")) {
                icon = getDrawable(R.drawable.ic_fake_video);
            } else {
                ResolveInfo ri = pm.resolveActivity(launchIntent(cn), 0);
                icon = (ri != null) ? ri.loadIcon(pm) : null;
            }
            ImageView iv = new ImageView(this);
            if (icon != null) iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setClickable(true);
            iv.setOnClickListener(v -> { hap(v); launch(cn); });
            iv.setOnLongClickListener(v -> { hap(v); confirmRemove(cn); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(BAR_ICON_DP), dp(BAR_ICON_DP));
            lp.leftMargin = dp(6); lp.rightMargin = dp(6);
            bottomBar.addView(iv, lp);
        }
    }

    /**
     * Home root that turns the off-screen physical edge bar (a full-height touch strip at screen
     * x ~257-341 px) into a volume slider: it intercepts only VERTICAL drags inside that strip and
     * routes them to the ControlWidget across the full screen height. Taps (gear, dock), horizontal
     * page-swipes, the top-centre notification shade and the left-edge back gesture all flow through.
     */
    private class HomeRoot extends FrameLayout {
        private float downX, downY;
        private boolean intercepting;
        // Require a CLEAR, deliberate vertical drag before the edge strip steals the touch, so a tap
        // (or a tap with a small finger wobble) near the clock no longer slams the volume slider.
        private final int dragMin;

        HomeRoot(Context c) {
            super(c);
            dragMin = dp(26);   // ~6% of screen height — well past any tap jitter
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); intercepting = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!intercepting && downX >= STRIP_LEFT_PX) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (Math.abs(dy) > dragMin && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                            intercepting = true;
                            return true;            // steal a deliberate vertical strip drag -> volume slide
                        }
                    }
                    return false;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (controlWidget == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float h = getHeight();
                    if (h > 0) {
                        float level = (h - e.getY()) / h;            // top = 1.0, bottom = 0.0
                        level = level < 0 ? 0 : level > 1 ? 1 : level;
                        controlWidget.edgeSlideTo(level);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    controlWidget.edgeSlideEnd();
                    intercepting = false;
                    return true;
            }
            return true;
        }
    }

    /** A dark circular chip with a white glyph — the launcher's button style. */
    private class ChipButton extends View {
        static final int GLYPH_APPS = 0, GLYPH_GEAR = 1;
        private final int glyph;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        ChipButton(Context c, int glyph) {
            super(c);
            this.glyph = glyph;
            p.setColor(Color.WHITE);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xFF1C1C1C);
            bg.setStroke(dp(1), 0xFF3A3A3A);
            setBackground(bg);
        }
        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            if (glyph == GLYPH_APPS) drawApps(cv); else drawGear(cv);
        }
        private void drawApps(Canvas cv) {
            float w = getWidth();
            float pad = w * 0.30f, gap = w * 0.12f;
            float cell = (w - 2 * pad - gap) / 2f, rr = cell * 0.25f;
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 2; i++)
                for (int j = 0; j < 2; j++) {
                    float l = pad + i * (cell + gap), t = pad + j * (cell + gap);
                    cv.drawRoundRect(l, t, l + cell, t + cell, rr, rr, p);
                }
        }
        private void drawGear(Canvas cv) {
            float w = getWidth(), cx = w / 2f, cy = w / 2f;
            float bodyR = w * 0.20f, toothLen = w * 0.11f, toothW = w * 0.12f;
            p.setStyle(Paint.Style.FILL);
            for (int k = 0; k < 8; k++) {
                cv.save();
                cv.rotate(k * 45f, cx, cy);
                r.set(cx - toothW / 2f, cy - bodyR - toothLen, cx + toothW / 2f, cy - bodyR + toothLen * 0.4f);
                cv.drawRoundRect(r, dp(1), dp(1), p);
                cv.restore();
            }
            cv.drawCircle(cx, cy, bodyR, p);
            // punch the center hole in the chip's background colour
            p.setColor(0xFF1C1C1C);
            cv.drawCircle(cx, cy, bodyR * 0.42f, p);
            p.setColor(Color.WHITE);
        }
    }

    /** Nothing-style 5-segment battery gauge. */
    private class BatteryBars extends View {
        private int pct = 100;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        BatteryBars(Context c) { super(c); }
        void setPct(int v) { pct = v; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            float w = getWidth(), h = getHeight();
            float stroke = dp(2);
            float nub = w * 0.05f;
            float left = stroke / 2f, top = stroke / 2f, bottom = h - stroke / 2f;
            float right = w - nub - stroke / 2f;
            float rad = h * 0.28f;

            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            r.set(left, top, right, bottom);
            cv.drawRoundRect(r, rad, rad, p);

            p.setStyle(Paint.Style.FILL);
            r.set(right, h * 0.34f, right + nub + stroke / 2f, h * 0.66f);
            cv.drawRoundRect(r, nub * 0.6f, nub * 0.6f, p);

            int filled = pct <= 0 ? 0 : Math.min(5, Math.max(1, (int) Math.ceil(pct / 20.0)));
            float pad = stroke * 1.7f;
            float iL = left + pad, iR = right - pad, iT = top + pad, iB = bottom - pad;
            float gap = dp(2);
            float barW = (iR - iL - 4 * gap) / 5f;
            float br = barW * 0.35f;
            for (int i = 0; i < 5; i++) {
                float bl = iL + i * (barW + gap);
                p.setColor(i < filled ? 0xFFFFFFFF : 0x33FFFFFF);
                r.set(bl, iT, bl + barW, iB);
                cv.drawRoundRect(r, br, br, p);
            }
        }
    }

    // ---- All-apps drawer (pick up to 4 for the bar) --------------------------

    private View buildDrawerPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(EDGE_INSET), dp(12), dp(8));

        // --- Big, visible, tappable action chips: Home / Close / IKKO ---
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 0, 0, dp(6));

        TextView homeChip = drawerChip("Home", 0xFF202020);
        homeChip.setOnClickListener(v -> { hap(v); showHome(); });
        actions.addView(homeChip, chipLp(0));

        TextView closeChip = drawerChip("Close", 0xFF7A2E2E);
        closeChip.setOnClickListener(v -> { hap(v); closeBackgroundApps(); });
        actions.addView(closeChip, chipLp(dp(8)));

        TextView ikkoChip = drawerChip("IKKO", 0xFF274472);
        ikkoChip.setOnClickListener(v -> { hap(v); launchIkkoLauncher(); });
        actions.addView(ikkoChip, chipLp(dp(8)));
        root.addView(actions);

        drawerCount = new TextView(this);
        drawerCount.setTypeface(dotFont);
        drawerCount.setTextColor(0xFF999999);
        drawerCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        drawerCount.setGravity(Gravity.END);
        drawerCount.setPadding(0, 0, dp(2), dp(6));
        root.addView(drawerCount);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        drawerContainer = new LinearLayout(this);
        drawerContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(drawerContainer);
        root.addView(scroll);

        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private void refreshDrawer() {
        if (drawerContainer == null) return;
        drawerContainer.removeAllViews();
        PackageManager pm = getPackageManager();
        List<ComponentName> pins = loadPins();
        drawerCount.setText("On bar  " + Math.min(pins.size(), MAX_BAR) + "/" + MAX_BAR);

        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(probe, 0);
        final List<ComponentName> comps = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (ResolveInfo ri : all) {
            comps.add(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
            labels.add(ri.loadLabel(pm).toString());
        }
        Integer[] order = new Integer[comps.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return labels.get(a).compareToIgnoreCase(labels.get(b));
            }
        });

        LinearLayout row = null;
        int placed = 0;
        for (int oi = 0; oi < order.length; oi++) {
            int idx = order[oi];
            if (placed % DRAWER_COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                drawerContainer.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            ComponentName cn = comps.get(idx);
            row.addView(buildDrawerCell(pm, cn, labels.get(idx), pins.contains(cn)));
            placed++;
        }
        if (row != null && placed % DRAWER_COLS != 0) {
            for (int k = placed % DRAWER_COLS; k < DRAWER_COLS; k++) {
                View spacer = new View(this);
                row.addView(spacer, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
        }
    }

    private View buildDrawerCell(PackageManager pm, ComponentName cn, String label, boolean pinned) {
        ResolveInfo ri = pm.resolveActivity(launchIntent(cn), 0);
        Drawable icon = (ri != null) ? ri.loadIcon(pm) : null;

        FrameLayout iconWrap = new FrameLayout(this);
        LinearLayout.LayoutParams iwLp = new LinearLayout.LayoutParams(
                dp(DRAWER_ICON_DP + 10), dp(DRAWER_ICON_DP + 10));
        iwLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconWrap.setLayoutParams(iwLp);

        ImageView iv = new ImageView(this);
        if (icon != null) iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
                dp(DRAWER_ICON_DP), dp(DRAWER_ICON_DP));
        ivLp.gravity = Gravity.CENTER;
        iconWrap.addView(iv, ivLp);

        if (pinned) {
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextColor(Color.WHITE);
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            check.setGravity(Gravity.CENTER);
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.OVAL);
            badge.setColor(0xFF2E7D32);
            badge.setStroke(dp(2), Color.BLACK);
            check.setBackground(badge);
            FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(dp(22), dp(22));
            cLp.gravity = Gravity.TOP | Gravity.END;
            iconWrap.addView(check, cLp);
        }

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(6), dp(12), dp(6), dp(12));
        cell.setClickable(true);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cell.addView(iconWrap);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTypeface(dotFont);
        name.setTextColor(pinned ? Color.WHITE : 0xFFCCCCCC);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, DRAWER_LABEL_SP);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = dp(8);
        cell.addView(name, nLp);

        cell.setOnClickListener(v -> { hap(v); launch(cn); showHome(); });          // tap -> open app
        cell.setOnLongClickListener(v -> { hap(v); toggleBar(cn); return true; });   // hold -> pin/unpin bar
        return cell;
    }

    private void toggleBar(ComponentName cn) {
        List<ComponentName> pins = loadPins();
        if (pins.contains(cn)) {
            pins.remove(cn);
        } else {
            if (pins.size() >= MAX_BAR) {
                Toast.makeText(this, "Bar holds " + MAX_BAR + " — remove one first",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            pins.add(cn);
        }
        savePins(pins);
        refreshDrawer();
        refreshBar();
    }

    private void confirmRemove(ComponentName cn) {
        List<ComponentName> pins = loadPins();
        pins.remove(cn);
        savePins(pins);
        refreshBar();
        Toast.makeText(this, "Removed from bar", Toast.LENGTH_SHORT).show();
    }

    // ---- Launching ------------------------------------------------------------

    private Intent launchIntent(ComponentName cn) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn);
    }

    private void launch(ComponentName cn) {
        try {
            Intent i = launchIntent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open app", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Pins persistence -----------------------------------------------------

    private List<ComponentName> loadPins() {
        List<ComponentName> out = new ArrayList<>();
        String raw = prefs.getString(KEY_PINS, "");
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            int slash = line.indexOf('/');
            if (slash > 0 && slash < line.length() - 1) {
                out.add(new ComponentName(line.substring(0, slash), line.substring(slash + 1)));
            }
        }
        return out;
    }

    private void savePins(List<ComponentName> pins) {
        StringBuilder sb = new StringBuilder();
        for (ComponentName cn : pins) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(cn.getPackageName()).append('/').append(cn.getClassName());
        }
        prefs.edit().putString(KEY_PINS, sb.toString()).apply();
    }

    /** First-run convenience: pin a few apps that exist, so the bar isn't empty. */
    private void maybeSeedDefaults() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return;
        // Settings has its own top-right button, so it's intentionally not seeded here.
        String[] wanted = {
                "com.mw.dialer", "com.mw.claude",
                "app.revanced.android.youtube",
                "app.revanced.android.apps.youtube.music",
                "org.schabi.newpipe"
        };
        PackageManager pm = getPackageManager();
        List<ComponentName> seed = new ArrayList<>();
        for (String pkg : wanted) {
            if (seed.size() >= MAX_BAR) break;
            Intent li = pm.getLaunchIntentForPackage(pkg);
            if (li != null && li.getComponent() != null) seed.add(li.getComponent());
        }
        if (!seed.isEmpty()) savePins(seed);
        prefs.edit().putBoolean(KEY_SEEDED, true).apply();
    }

    // ---- Navigation -----------------------------------------------------------

    private void showDrawer() { refreshDrawer(); flipper.setDisplayedChild(1); }
    private void showHome() { flipper.setDisplayedChild(0); }

    /** Kill background processes of every third-party app — a "close all". */
    private void closeBackgroundApps() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            PackageManager pm = getPackageManager();
            int n = 0;
            for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (ai.packageName.equals(getPackageName())) continue;
                am.killBackgroundProcesses(ai.packageName);
                n++;
            }
            // Background processes killed (frees memory); now open Recents so the
            // user can actually swipe the app cards away — the visible "close".
            if (AncAccessibilityService.instance != null && AncAccessibilityService.instance.openRecents()) {
                showHome();
            } else {
                Toast.makeText(this, "Closed background apps", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't close apps", Toast.LENGTH_SHORT).show();
        }
    }

    /** Jump straight into IKKO's launcher (Home returns to ours). */
    private void launchIkkoLauncher() {
        try {
            Intent i = new Intent();
            i.setClassName("com.ikkoaudio.launcher", "com.ikkoaudio.launcher.ui.main.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onBackPressed() {
        if (flipper != null && flipper.getDisplayedChild() != 0) showHome();
        // else swallow — a launcher must not exit on Back.
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (flipper != null) showHome();
    }

    // ---- Clock + battery tickers ----------------------------------------------

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0 && batteryBars != null) {
                batteryBars.setPct(level * 100 / scale);
            }
            updateCaseBattery(i);   // feeds the case-battery stat row (was polled in updateStats)
        }
    };

    // Battery: stop ALL periodic work when the display is off (the launcher may stay "resumed" under
    // the firmware's ambient mode, so we gate on the real screen state, not just onPause).
    private boolean monitoring;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) startMonitoring();
            else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) stopMonitoring();
        }
    };
    private android.database.ContentObserver alertObserver;

    private void updateClock() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));
        dateView.setText(dateFmt.format(now));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBar();
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, sf);
        registerAlertObserver();
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isInteractive()) startMonitoring();   // only run periodic work if the screen is on
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopMonitoring();
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        unregisterAlertObserver();
    }

    /** Begin all periodic work (clock/stats poll/signal listener/media + marquee). Idempotent. */
    private void startMonitoring() {
        if (monitoring) return;
        monitoring = true;
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        updateClock();
        refreshMediaController();
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            tm.listen(phoneListener, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        } catch (Exception ignored) {}
        setMarqueesPaused(false);
        statsHandler.removeCallbacks(statsTick);
        statsHandler.post(statsTick);     // runs updateStats immediately, then every STATS_INTERVAL_MS
        refreshAlerts();                  // fresh badge/clock-width state on wake
    }

    /** Stop all periodic work — called on screen-off and on pause. Idempotent. */
    private void stopMonitoring() {
        if (!monitoring) return;
        monitoring = false;
        try { unregisterReceiver(timeReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        if (mediaController != null) { try { mediaController.unregisterCallback(mediaCb); } catch (Exception ignored) {} }
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            tm.listen(phoneListener, android.telephony.PhoneStateListener.LISTEN_NONE);
        } catch (Exception ignored) {}
        statsHandler.removeCallbacks(statsTick);
        setMarqueesPaused(true);          // freeze the 60fps scrollers
    }

    private void setMarqueesPaused(boolean paused) {
        MarqueeText[] all = { earbudInfo, tvCase, tvWifi, tvCell, tvBt };
        for (MarqueeText m : all) {
            if (m == null) continue;
            if (paused) m.pauseTicker(); else m.resumeTicker();
        }
    }

    /** Event-driven missed-call / unread-SMS badges — re-query only when the providers change. */
    private void registerAlertObserver() {
        if (alertObserver != null) return;
        alertObserver = new android.database.ContentObserver(statsHandler) {
            @Override public void onChange(boolean self) { refreshAlerts(); }
        };
        try { getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, alertObserver); } catch (Exception ignored) {}
        try { getContentResolver().registerContentObserver(Uri.parse("content://sms"), true, alertObserver); } catch (Exception ignored) {}
    }

    private void unregisterAlertObserver() {
        if (alertObserver != null) {
            try { getContentResolver().unregisterContentObserver(alertObserver); } catch (Exception ignored) {}
            alertObserver = null;
        }
    }

    // ---- Media (right control panel) ------------------------------------------

    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { updateMediaUi(); }
        @Override public void onMetadataChanged(MediaMetadata m) { updateMediaUi(); }
    };

    private void refreshMediaController() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            java.util.List<MediaController> cs = msm.getActiveSessions(
                    new ComponentName(this, MediaNotifListener.class));
            MediaController pick = null;
            for (MediaController c : cs) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
                if (pick == null) pick = c;
            }
            android.util.Log.i("Media", "sessions=" + cs.size()
                    + " pick=" + (pick == null ? "null" : pick.getPackageName()));
            if (pick != mediaController) {
                if (mediaController != null) try { mediaController.unregisterCallback(mediaCb); } catch (Exception ignored) {}
                mediaController = pick;
                if (mediaController != null) mediaController.registerCallback(mediaCb);
            }
        } catch (SecurityException e) {
            android.util.Log.w("Media", "no notif access: " + e.getMessage());
            mediaController = null;   // notification access not granted yet
        } catch (Exception e) {
            android.util.Log.w("Media", "err", e);
        }
        updateMediaUi();
    }

    private void updateMediaUi() {
        if (controlWidget == null) return;
        String title = "—";
        boolean playing = false;
        if (mediaController != null) {
            MediaMetadata md = mediaController.getMetadata();
            if (md != null) {
                CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
                if (t != null) title = t.toString();
            }
            PlaybackState s = mediaController.getPlaybackState();
            playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        }
        controlWidget.setMedia(title, playing);
    }

    // Control via media-button events — no permission needed, reaches the active player.
    @Override public void mediaPlayPause() { sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); }
    @Override public void mediaNext()      { sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT); }
    @Override public void mediaPrev()      { sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); }

    private void sendMediaKey(int code) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
        } catch (Exception ignored) {}
    }

    private LinearLayout.LayoutParams marginTop(int dpVal) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(dpVal);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }
}
