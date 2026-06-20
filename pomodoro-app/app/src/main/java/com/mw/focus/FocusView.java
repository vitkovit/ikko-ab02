package com.mw.focus;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * A self-contained focus / break interval timer drawn entirely in the IKKO
 * "dotted clock" idiom: white dot-matrix glyphs and a depleting ring of dots on
 * pure black, tuned for the AB02's 368x448 @160dpi round-cornered screen.
 *
 *  - Big dot-matrix MM:SS countdown (hero), blinking colon while running.
 *  - A 60-dot progress ring that depletes clockwise as the phase runs down.
 *  - Phase label (FOCUS / BREAK / LONG BREAK) in small dot text.
 *  - Session pips toward the long break.
 *  - Reset / Start-Pause / Skip controls, plus - / + duration tuners when idle.
 *  - Beep + haptics on phase change, auto-advancing the focus/break cycle.
 */
public class FocusView extends View {

    // ---- phases ----
    private static final int FOCUS = 0, SHORT_BREAK = 1, LONG_BREAK = 2;
    private static final int LONG_EVERY = 4;       // long break after N focus sessions
    private static final int RING_DOTS = 120;      // 2 dots per second -> full sweep each minute

    // durations (seconds), persisted
    private int focusSec = 25 * 60, shortSec = 5 * 60, longSec = 15 * 60;
    private int completedFocus = 0;                // toward the long break

    private int phase = FOCUS;
    private long phaseTotalMs;
    private long remainingMs;                      // authoritative when paused
    private long endTime;                          // SystemClock target when running
    private boolean running = false;

    // geometry, computed in onSizeChanged (px == dp at 160dpi, but we scale anyway)
    private float density;
    private float cx, cy, ringR;
    private float timerPitch, timerDotR;
    private float labelPitch, labelDotR;
    private float clockPitch, clockDotR;
    private float heroTop, heroH;                  // dot-matrix countdown band
    private float clockTop, labelTop, chevUpY, chevDownY;
    private float btnY, btnMainR, btnSideR, btnSideOff;
    private float mediaY, mediaR, mediaOff;        // background-music transport, top of ring

    // drag-to-set state (idle only): vertical swipe over the timer changes the duration.
    // dragField 0 = minutes (left half), 1 = seconds (right half).
    private boolean draggingSet = false;
    private int dragField = 0;
    private float dragLastY, dragAccum;

    // paints
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Handler handler = new Handler();
    private ToneGenerator tone;
    private SharedPreferences prefs;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            remainingMs = endTime - now;
            if (remainingMs <= 0) {
                remainingMs = 0;
                completePhase();
            }
            invalidate();
            handler.postDelayed(this, 80);
        }
    };

    public FocusView(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);

        prefs = ctx.getSharedPreferences("focus", Context.MODE_PRIVATE);
        focusSec = prefs.getInt("focusSec", 25 * 60);
        shortSec = prefs.getInt("shortSec", 5 * 60);
        longSec  = prefs.getInt("longSec", 15 * 60);
        completedFocus = prefs.getInt("completedFocus", 0);

        on.setColor(Color.WHITE);
        on.setStyle(Paint.Style.FILL);
        dim.setColor(0xFF2C2C2C);
        dim.setStyle(Paint.Style.FILL);
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        ring.setColor(Color.WHITE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);

        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (Exception ignored) {}

        setPhase(FOCUS, false);
    }

    private float dp(float v) { return v * density; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        ringR = Math.min(w, h) / 2f - dp(13);

        // size the hero countdown to ~80% of the ring diameter (big and bold)
        float targetW = (ringR * 2f) * 0.80f;
        timerPitch = targetW / DotFont.measure("00:00", 1f); // measure with pitch=1 -> column count
        timerDotR = timerPitch * 0.42f;

        // clock (top) and phase label (bottom) share one font size so they mirror exactly
        labelPitch = dp(3.3f);
        labelDotR = labelPitch * 0.42f;
        clockPitch = labelPitch;
        clockDotR = labelDotR;

        // Symmetric mirror across the timer (the big number is the axis):
        //   clock (top)  /  media controls  /  TIMER  /  timer controls  /  phase label (bottom)
        heroH = DotFont.ROWS * timerPitch;
        heroTop = cy - heroH / 2f;                  // timer dead-centre = mirror axis
        float numTop = heroTop, numBottom = heroTop + heroH;

        // clock & phase label, mirrored equal distances from the centre
        float outerOff = dp(132);
        float clockH = DotFont.ROWS * clockPitch;
        float labelH = DotFont.ROWS * labelPitch;
        clockTop = (cy - outerOff) - clockH / 2f;
        labelTop = (cy + outerOff) - labelH / 2f;

        // bigger control circles, all identical
        float ctrlR = dp(22);
        float ctrlOff = dp(64);
        mediaR = ctrlR;  mediaOff = ctrlOff;
        btnMainR = ctrlR; btnSideR = ctrlR; btnSideOff = ctrlOff;

        // icon rows sit at the VISUAL midpoint of the gap between the number and the clock / label
        mediaY = ((clockTop + clockH) + numTop) / 2f;   // between clock-bottom and number-top
        btnY   = (numBottom + labelTop) / 2f;           // between number-bottom and label-top

        // scroll-to-set hint chevrons hug the timer
        chevUpY = heroTop - dp(7);
        chevDownY = heroTop + heroH + dp(7);

        stroke.setStrokeWidth(dp(2.4f));
    }

    // ---------------- timer state machine ----------------

    private int secondsFor(int p) {
        return p == FOCUS ? focusSec : p == SHORT_BREAK ? shortSec : longSec;
    }

    private void setPhase(int p, boolean autoStart) {
        phase = p;
        phaseTotalMs = secondsFor(p) * 1000L;
        remainingMs = phaseTotalMs;
        running = false;
        if (autoStart) start();
        else { setKeepScreenOn(false); invalidate(); }
    }

    private void start() {
        if (remainingMs <= 0) remainingMs = phaseTotalMs;
        endTime = SystemClock.elapsedRealtime() + remainingMs;
        running = true;
        setKeepScreenOn(true);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        invalidate();
    }

    private void pause() {
        if (running) remainingMs = endTime - SystemClock.elapsedRealtime();
        running = false;
        setKeepScreenOn(false);
        handler.removeCallbacks(ticker);
        invalidate();
    }

    private void toggle() { if (running) pause(); else start(); }

    private void reset() {
        running = false;
        handler.removeCallbacks(ticker);
        remainingMs = phaseTotalMs;
        setKeepScreenOn(false);
        invalidate();
    }

    /** Move to the next phase in the cycle. {@code count}=true when a focus session truly finished. */
    private void advance(boolean count, boolean autoStart) {
        int next;
        if (phase == FOCUS) {
            if (count) { completedFocus++; }
            int reached = count ? completedFocus : completedFocus + 1;
            next = (reached % LONG_EVERY == 0) ? LONG_BREAK : SHORT_BREAK;
        } else {
            if (phase == LONG_BREAK) completedFocus = 0; // new cycle
            next = FOCUS;
        }
        save();
        setPhase(next, autoStart);
    }

    private void completePhase() {
        beep();
        vibrate();
        advance(true, true); // auto-start the next phase for continuous flow
    }

    private void skip() {
        advance(false, running); // keep whatever run state we were in
    }

    /** field 0 = minutes (+/-60s), field 1 = seconds (+/-1s); applies to the current phase when idle. */
    private void adjust(int field, int delta) {
        if (running) return;
        int max = phase == FOCUS ? 120 * 60 : 60 * 60;
        int step = field == 0 ? 60 : 1;
        int cur = clamp(secondsFor(phase) + delta * step, 5, max);
        if (phase == FOCUS) focusSec = cur;
        else if (phase == SHORT_BREAK) shortSec = cur;
        else longSec = cur;
        phaseTotalMs = cur * 1000L;
        remainingMs = phaseTotalMs;
        save();
        invalidate();
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    private void save() {
        prefs.edit()
            .putInt("focusSec", focusSec)
            .putInt("shortSec", shortSec)
            .putInt("longSec", longSec)
            .putInt("completedFocus", completedFocus)
            .apply();
    }

    private void beep() {
        if (tone == null) return;
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
        } catch (Exception ignored) {}
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            long[] pattern = {0, 140, 90, 140};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {}
    }

    // ---------------- drawing ----------------

    private String phaseLabel() {
        return phase == FOCUS ? "FOCUS" : phase == SHORT_BREAK ? "BREAK" : "LONG BREAK";
    }

    @Override protected void onDraw(Canvas c) {
        drawRing(c);

        // wall clock, inside the ring near the top, bright
        Calendar cal = Calendar.getInstance();
        String wall = String.format(Locale.US, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        float ww = DotFont.measure(wall, clockPitch);
        DotFont.draw(c, wall, cx - ww / 2f, clockTop, clockPitch, clockDotR, on);

        // phase label above the hero
        String label = phaseLabel();
        float lw = DotFont.measure(label, labelPitch);
        DotFont.draw(c, label, cx - lw / 2f, labelTop, labelPitch, labelDotR, on);

        // hero countdown MM:SS (steady colon)
        long shown = Math.max(0, remainingMs);
        int totalSec = (int) ((shown + 999) / 1000); // ceil so it hits 00:00 exactly at end
        int mm = totalSec / 60, ss = totalSec % 60;
        String time = String.format(Locale.US, "%02d:%02d", mm, ss);
        float tw = DotFont.measure(time, timerPitch);
        DotFont.draw(c, time, cx - tw / 2f, heroTop, timerPitch, timerDotR, on);

        drawMedia(c);
        if (!running) drawSetHint(c);
        drawButtons(c);
    }

    /** Background-music transport (prev / music play-pause / next) at the top of the ring. */
    private void drawMedia(Canvas c) {
        float px = cx - mediaOff, nx = cx + mediaOff;
        drawCircleOutline(c, px, mediaY, mediaR);
        drawCircleOutline(c, cx, mediaY, mediaR);
        drawCircleOutline(c, nx, mediaY, mediaR);
        float s = mediaR * 0.5f;
        // prev: bar + left triangle
        c.drawRect(px - s, mediaY - s, px - s * 0.62f, mediaY + s, fill);
        Path back = new Path();
        back.moveTo(px + s, mediaY - s);
        back.lineTo(px + s, mediaY + s);
        back.lineTo(px - s * 0.3f, mediaY);
        back.close();
        c.drawPath(back, fill);
        // centre: eighth-note (identity = music; tap toggles play/pause)
        float headR = mediaR * 0.30f;
        float headCx = cx - mediaR * 0.18f, headCy = mediaY + mediaR * 0.36f;
        c.drawCircle(headCx, headCy, headR, fill);
        float stemX = headCx + headR;
        stroke.setStrokeWidth(dp(1.8f));
        c.drawLine(stemX, headCy, stemX, mediaY - mediaR * 0.55f, stroke);
        c.drawLine(stemX, mediaY - mediaR * 0.55f, stemX + mediaR * 0.42f, mediaY - mediaR * 0.20f, stroke);
        stroke.setStrokeWidth(dp(2.4f));
        // next: forward skip
        drawSkipGlyph(c, nx, mediaY, s);
    }

    private void sendMedia(int keycode) {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keycode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keycode, 0));
        } catch (Exception ignored) {}
    }

    private void drawRing(Canvas c) {
        // dim 120-dot track — the dotted-clock bezel
        float r = dp(2.0f);
        float[] xs = new float[RING_DOTS];
        float[] ys = new float[RING_DOTS];
        for (int i = 0; i < RING_DOTS; i++) {
            double a = -Math.PI / 2 + (2 * Math.PI * i / RING_DOTS); // top, clockwise
            xs[i] = cx + (float) (ringR * Math.cos(a));
            ys[i] = cy + (float) (ringR * Math.sin(a));
            c.drawCircle(xs[i], ys[i], r, dim);
        }
        if (!running) return; // sweep only animates while counting down
        // progress = elapsed within the current minute -> fills 2 dots/sec, full sweep each minute
        long rem = Math.max(0, remainingMs);
        float minuteFrac = ((60000f - (rem % 60000)) % 60000f) / 60000f;
        int lit = Math.round(RING_DOTS * minuteFrac);
        if (lit <= 0) return;
        // connect the lit dots with a bright line, then light the dots themselves
        Path p = new Path();
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < lit && i < RING_DOTS; i++) p.lineTo(xs[i], ys[i]);
        ring.setStrokeWidth(dp(2.4f));
        c.drawPath(p, ring);
        for (int i = 0; i < lit && i < RING_DOTS; i++) c.drawCircle(xs[i], ys[i], dp(2.6f), on);
    }

    /** Faint up/down chevrons over BOTH the minutes and seconds groups — swipe to set. */
    private void drawSetHint(Canvas c) {
        stroke.setStrokeWidth(dp(1.8f));
        stroke.setColor(0xFF6E6E6E);
        float w = dp(8), h = dp(5);
        float xMM = cx - 9f * timerPitch;   // centre of the minutes pair
        float xSS = cx + 9f * timerPitch;   // centre of the seconds pair
        for (float gx : new float[]{xMM, xSS}) {
            // up chevron
            c.drawLine(gx - w, chevUpY, gx, chevUpY - h, stroke);
            c.drawLine(gx, chevUpY - h, gx + w, chevUpY, stroke);
            // down chevron
            c.drawLine(gx - w, chevDownY, gx, chevDownY + h, stroke);
            c.drawLine(gx, chevDownY + h, gx + w, chevDownY, stroke);
        }
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(dp(2.4f));
    }

    private void drawButtons(Canvas c) {
        // reset (left)
        drawCircleOutline(c, cx - btnSideOff, btnY, btnSideR);
        drawResetGlyph(c, cx - btnSideOff, btnY, btnSideR * 0.5f);
        // skip (right)
        drawCircleOutline(c, cx + btnSideOff, btnY, btnSideR);
        drawSkipGlyph(c, cx + btnSideOff, btnY, btnSideR * 0.5f);
        // play / pause (centre, filled emphasis)
        drawCircleOutline(c, cx, btnY, btnMainR);
        if (running) drawPauseGlyph(c, cx, btnY, btnMainR * 0.42f);
        else drawPlayGlyph(c, cx, btnY, btnMainR * 0.5f);
    }

    private void drawCircleOutline(Canvas c, float x, float y, float r) {
        stroke.setStrokeWidth(dp(2.2f));
        c.drawCircle(x, y, r, stroke);
    }

    private void drawPlayGlyph(Canvas c, float x, float y, float s) {
        Path p = new Path();
        p.moveTo(x - s * 0.55f, y - s);
        p.lineTo(x - s * 0.55f, y + s);
        p.lineTo(x + s * 0.85f, y);
        p.close();
        c.drawPath(p, fill);
    }

    private void drawPauseGlyph(Canvas c, float x, float y, float s) {
        float bw = s * 0.7f, gap = s * 0.55f;
        c.drawRect(x - gap - bw, y - s, x - gap, y + s, fill);
        c.drawRect(x + gap, y - s, x + gap + bw, y + s, fill);
    }

    private void drawSkipGlyph(Canvas c, float x, float y, float s) {
        Path p = new Path();
        p.moveTo(x - s, y - s);
        p.lineTo(x - s, y + s);
        p.lineTo(x + s * 0.3f, y);
        p.close();
        c.drawPath(p, fill);
        c.drawRect(x + s * 0.45f, y - s, x + s * 0.8f, y + s, fill);
    }

    private void drawResetGlyph(Canvas c, float x, float y, float s) {
        RectF oval = new RectF(x - s, y - s, x + s, y + s);
        stroke.setStrokeWidth(dp(2.0f));
        c.drawArc(oval, -50, 285, false, stroke);
        // arrowhead at the arc's start (top-right)
        double a = Math.toRadians(-50);
        float ax = x + (float) (s * Math.cos(a));
        float ay = y + (float) (s * Math.sin(a));
        Path head = new Path();
        head.moveTo(ax, ay);
        head.lineTo(ax - s * 0.55f, ay - s * 0.15f);
        head.lineTo(ax + s * 0.05f, ay + s * 0.6f);
        head.close();
        c.drawPath(head, fill);
    }

    // ---------------- touch ----------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // background-music transport (top row)
                if (hit(x, y, cx - mediaOff, mediaY, mediaR + dp(8))) { sendMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return true; }
                if (hit(x, y, cx, mediaY, mediaR + dp(8))) { sendMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return true; }
                if (hit(x, y, cx + mediaOff, mediaY, mediaR + dp(8))) { sendMedia(KeyEvent.KEYCODE_MEDIA_NEXT); return true; }
                if (hit(x, y, cx, btnY, btnMainR + dp(10))) { toggle(); return true; }
                if (hit(x, y, cx - btnSideOff, btnY, btnSideR + dp(14))) { reset(); return true; }
                if (hit(x, y, cx + btnSideOff, btnY, btnSideR + dp(14))) { skip(); return true; }
                // start a drag-to-set on the timer band when idle; left half = minutes, right = seconds
                if (!running && inHeroBand(x, y)) {
                    draggingSet = true;
                    dragField = (x < cx) ? 0 : 1;
                    dragLastY = y;
                    dragAccum = 0f;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingSet) {
                    dragAccum += (dragLastY - y); // swipe up => positive => more
                    dragLastY = y;
                    float step = dp(12);
                    while (dragAccum >= step)  { adjust(dragField, +1); dragAccum -= step; }
                    while (dragAccum <= -step) { adjust(dragField, -1); dragAccum += step; }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingSet = false;
                return true;
        }
        return true;
    }

    /** The vertical band around the hero countdown that accepts the set-duration swipe. */
    private boolean inHeroBand(float x, float y) {
        return y >= heroTop - dp(28) && y <= heroTop + heroH + dp(28)
                && x >= cx - ringR * 0.85f && x <= cx + ringR * 0.85f;
    }

    private boolean hit(float x, float y, float bx, float by, float r) {
        float dx = x - bx, dy = y - by;
        return dx * dx + dy * dy <= r * r;
    }

    // ---------------- lifecycle ----------------

    void onResume() { if (running) { handler.post(ticker); } invalidate(); }
    void onPause()  { handler.removeCallbacks(ticker); save(); }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(ticker);
        if (tone != null) { try { tone.release(); } catch (Exception ignored) {} tone = null; }
    }
}
