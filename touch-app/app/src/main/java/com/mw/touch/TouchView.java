package com.mw.touch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Digitizer / slider-zone test surface. Starts all-white; every touched pixel turns green and
 * stays green, so a slide (incl. the edge capacitive strip) leaves a visible trail of exactly
 * which pixels the panel reports. On top of that it overlays the launcher's TWO real slider
 * hit-regions, computed identically to the launcher, color-coded:
 *   - BLUE  = edge strip  -> SLIDE only   (HomeRoot: x >= STRIP_LEFT_PX, full height, vertical drag)
 *   - ORANGE = on-screen rail -> TAP + SLIDE (ControlWidget right rail: right railW of the widget band)
 * Live X/Y + pointer count are shown top-left. A volume key clears the trail.
 */
public class TouchView extends View {

    // --- mirror of the launcher's geometry constants -------------------------
    private static final int STRIP_LEFT_PX = 255;   // MainActivity.STRIP_LEFT_PX (pixels)
    // ControlWidget layout margins (MainActivity.buildHomePage), in dp
    private static final int WIDGET_LEFT_DP = 40, WIDGET_TOP_DP = 72,
                             WIDGET_RIGHT_DP = 2, WIDGET_BOTTOM_DP = 96;
    private static final int RAIL_W_DP = 86;        // ControlWidget.railW()
    // bar track within the rail (ControlWidget.drawPage): local top=16, barTop=top+48, barBot=h-38
    private static final int BAR_TOP_DP = 16 + 48, BAR_BOT_FROM_BOTTOM_DP = 38;

    private Bitmap bmp;
    private Canvas bc;
    private final Paint green = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final float density;
    private float dotR;
    private boolean showZones = true;
    private String hud = "touch anywhere";

    public TouchView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        dotR = density * 2.5f;
        green.setColor(0xFF00C853);   // green
        green.setStyle(Paint.Style.FILL);
        text.setColor(0xFF202020);
        text.setTextSize(density * 13f);
        zoneFill.setStyle(Paint.Style.FILL);
        zoneStroke.setStyle(Paint.Style.STROKE);
        zoneStroke.setStrokeWidth(density * 2f);
        label.setTextSize(density * 12f);
        label.setFakeBoldText(true);
        setKeepScreenOn(true);
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bc = new Canvas(bmp);
        bc.drawColor(Color.WHITE);
    }

    public void clear() {
        if (bc != null) { bc.drawColor(Color.WHITE); invalidate(); }
        hud = "cleared";
    }

    /** Volume-key toggles the zone overlay on/off so the bare trail can be inspected. */
    public void toggleZones() { showZones = !showZones; invalidate(); }

    private void paintPointer(float x, float y) {
        if (bc != null) bc.drawCircle(x, y, dotR, green);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int n = e.getPointerCount();
        // capture in-between (historical) samples too, so fast slides record every pixel
        for (int h = 0; h < e.getHistorySize(); h++)
            for (int p = 0; p < n; p++)
                paintPointer(e.getHistoricalX(p, h), e.getHistoricalY(p, h));
        for (int p = 0; p < n; p++)
            paintPointer(e.getX(p), e.getY(p));

        float x = e.getX(), y = e.getY();
        hud = "x=" + Math.round(x) + "  y=" + Math.round(y) + "   pts=" + n + "   [" + zoneAt(x, y) + "]";
        invalidate();
        return true;
    }

    /** Which launcher slider region (if any) owns this pixel — same tests the launcher uses. */
    private String zoneAt(float x, float y) {
        int w = getWidth(), h = getHeight();
        float widgetRight = w - dp(WIDGET_RIGHT_DP);
        float railLeft = widgetRight - dp(RAIL_W_DP);
        float widgetTop = dp(WIDGET_TOP_DP), widgetBot = h - dp(WIDGET_BOTTOM_DP);
        boolean onRail = x >= railLeft && x <= widgetRight && y >= widgetTop && y <= widgetBot;
        boolean onStrip = x >= STRIP_LEFT_PX;
        if (onRail && onStrip) return "RAIL+STRIP";
        if (onRail) return "RAIL tap/slide";
        if (onStrip) return "STRIP slide";
        return "pass-through";
    }

    @Override protected void onDraw(Canvas c) {
        if (bmp != null) c.drawBitmap(bmp, 0, 0, null);
        else c.drawColor(Color.WHITE);

        if (showZones) drawZones(c);

        c.drawText(hud, density * 10f, density * 22f, text);
        c.drawText("vol- = clear   vol+ = toggle zones", density * 10f, density * 40f, text);
    }

    private void drawZones(Canvas c) {
        int w = getWidth(), h = getHeight();

        // --- edge strip: SLIDE only (blue) — x >= STRIP_LEFT_PX, full height ---
        zoneFill.setColor(0x332962FF);
        c.drawRect(STRIP_LEFT_PX, 0, w, h, zoneFill);
        zoneStroke.setColor(0xFF2962FF);
        c.drawLine(STRIP_LEFT_PX, 0, STRIP_LEFT_PX, h, zoneStroke);
        label.setColor(0xFF1A48CC);
        c.drawText("SLIDE", STRIP_LEFT_PX + density * 6f, h - density * 10f, label);
        c.drawText("strip x>=" + STRIP_LEFT_PX, STRIP_LEFT_PX + density * 6f, h - density * 24f, label);

        // --- on-screen rail: TAP + SLIDE (orange) — right railW of the widget band ---
        float widgetRight = w - dp(WIDGET_RIGHT_DP);
        float railLeft = widgetRight - dp(RAIL_W_DP);
        float widgetTop = dp(WIDGET_TOP_DP), widgetBot = h - dp(WIDGET_BOTTOM_DP);
        zoneFill.setColor(0x33FF6D00);
        c.drawRect(railLeft, widgetTop, widgetRight, widgetBot, zoneFill);
        zoneStroke.setColor(0xFFFF6D00);
        rf.set(railLeft, widgetTop, widgetRight, widgetBot);
        c.drawRect(rf, zoneStroke);
        label.setColor(0xFFC75500);
        c.drawText("TAP+SLIDE", railLeft + density * 4f, widgetTop + density * 14f, label);

        // the actual drawn level-bar track inside the rail (where the % fill lives)
        float barTop = widgetTop + dp(BAR_TOP_DP);
        float barBot = widgetBot - dp(BAR_BOT_FROM_BOTTOM_DP);
        float railCx = widgetRight - dp(43);          // ControlWidget.drawPage cx = right - dp(43)
        float barW = dp(26), rad = barW / 2f;
        rf.set(railCx - barW / 2f, barTop, railCx + barW / 2f, barBot);
        zoneStroke.setColor(0xFFC75500);
        c.drawRoundRect(rf, rad, rad, zoneStroke);
    }
}
