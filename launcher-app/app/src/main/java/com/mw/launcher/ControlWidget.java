package com.mw.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Right-side control panel with three pages — Volume / Media / Brightness.
 * Swipe left/right to switch page (local touches, not the firmware edge gestures).
 * Swipe up/down to adjust the value; on the Media page, tap = play/pause and a
 * vertical flick = next/previous. Volume + brightness are applied directly; media
 * goes through the Host (a MediaController in the activity).
 */
public class ControlWidget extends View {

    public interface Host {
        void mediaPlayPause();
        void mediaNext();
        void mediaPrev();
    }

    public static final int VOLUME = 0, MEDIA = 1, BRIGHT = 2;

    private final float density;
    private final AudioManager am;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private int page = VOLUME;
    private float pageOffset;
    private boolean animating;
    private Host host;

    private String mediaTitle = "";
    private boolean playing = false;

    // gesture state
    private float downX, downY, lastY, volF, brightF, skipAccum;
    private boolean decided, horizontal, moved, onRail;
    private float barTop, barBot;   // current vol/bright bar geometry, for the absolute slider

    private float railW() { return dp(86); }            // touch zone for slide/tap is the right rail only

    // smooth slider: the bar eases toward the finger while pressed, and FREEZES on release.
    private static final float SLIDER_TAU = 0.16f;  // smoothing time constant (s); smaller = snappier
    private float fingerTarget, displayLevel;        // displayLevel = smooth float the bar draws
    private int sliderPage;
    private boolean sliderActive;
    private long sliderLast;

    public ControlWidget(Context c, Typeface font) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        tp.setColor(Color.WHITE);
        tp.setTypeface(font);
        tp.setTextAlign(Paint.Align.CENTER);
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    private void hap() {
        try {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignored) {}
    }

    public void setHost(Host h) { host = h; }

    public void setMedia(String title, boolean isPlaying) {
        mediaTitle = title == null ? "" : title;
        playing = isPlaying;
        if (page == MEDIA) invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (animating) return true;
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; lastY = y;
                decided = false; moved = false; skipAccum = 0;
                onRail = x > getWidth() - railW();    // vertical slide / tap only on the right rail
                volF = am == null ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
                brightF = readBrightness();
                // NOTE: do NOT requestDisallowInterceptTouchEvent here — the HomeRoot parent must be
                // free to steal vertical drags in the physical-edge strip and route them to edgeSlideTo().
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = x - downX, dy = y - downY;
                float adx = Math.abs(dx), ady = Math.abs(dy);
                if (!decided) {
                    // a clear sideways swipe anywhere -> page change; up/down only when on the rail
                    if (adx > dp(14) && adx > ady * 1.3f) { decided = true; moved = true; horizontal = true; }
                    // require a clear, deliberate vertical drag — a tap/wobble near the clock no longer slides
                    else if (onRail && ady > dp(26)) { decided = true; moved = true; horizontal = false; }
                }
                if (decided) {
                    if (horizontal) {
                        pageOffset = Math.max(0f, x - downX);   // only left-to-right drag rotates pages
                    } else if (page == MEDIA) {
                        adjust(lastY - y);          // media: relative flick = next/prev
                        lastY = y;
                    } else {
                        onSlideTo(y);               // volume / brightness: smooth slider
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopSlider();                        // freeze the bar on release
                if (decided && horizontal) settlePage();
                else if (!moved) onTap();
                else { pageOffset = 0; invalidate(); }
                return true;
        }
        return true;
    }

    private void onTap() {
        if (!onRail) return;                                   // taps only count on the rail
        float cy = getHeight() / 2f, gap = dp(66);
        if (page == MEDIA) {
            if (host == null) return;
            hap();
            if (downY < cy - gap * 0.5f) host.mediaPrev();     // tapped upper -> previous
            else if (downY > cy + gap * 0.5f) host.mediaNext();// tapped lower -> next
            else { playing = !playing; invalidate(); host.mediaPlayPause(); }
        } else {
            // ignore taps above/below the visible bar (e.g. up in the clock zone) — only the bar nudges
            if (barBot <= barTop || downY < barTop || downY > barBot) return;
            hap();
            nudge(downY < (barTop + barBot) / 2f ? +1 : -1);   // fine-tune: tap upper = +1, lower = -1 step
        }
    }

    private void nudge(int dir) {
        if (page == VOLUME && am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = (int) clamp(am.getStreamVolume(AudioManager.STREAM_MUSIC) + dir, 0, max);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        } else if (page == BRIGHT) {
            int b = (int) clamp(readBrightness() + dir * 14, 6, 255);
            writeBrightness(b);
        }
        invalidate();
    }

    /** Finger Y -> target on the visible bar while pressed. The bar smoothly eases toward it. */
    private void onSlideTo(float y) {
        if (barBot <= barTop) return;
        setTarget(clamp((barBot - y) / (barBot - barTop), 0f, 1f));
    }

    // Top / bottom 5% of the travel snaps straight to max / mute, bypassing the ease-and-freeze so a quick
    // flick to the edge reliably lands on exactly 15 or 0 instead of stopping a step or two short.
    private static final float SNAP = 0.05f;

    /** Set the slider's target level (0..1); seeds the easer from the real current value on the first call. */
    private void setTarget(float level) {
        level = clamp(level, 0f, 1f);
        if (level >= 1f - SNAP) level = 1f;          // snap to max
        else if (level <= SNAP) level = 0f;          // snap to mute
        boolean atEdge = level == 0f || level == 1f;
        fingerTarget = level;
        if (!sliderActive) {                         // begin from the current real value
            sliderActive = true;
            sliderPage = page;
            displayLevel = currentLevel(sliderPage);
            sliderLast = SystemClock.uptimeMillis();
            post(slider);
        }
        if (atEdge) {                                // jump past the easing so the extreme lands immediately
            displayLevel = level;
            applyLevel(sliderPage, level);
            invalidate();
        }
    }

    // --- physical edge-bar entry points (driven by HomeRoot's full-height strip interception) ---
    private boolean edgeStarted;
    private float lastEdgeLevel, edgeSkipAccum;

    /** level = 0..1 from the full-height strip (top=1). Volume/brightness ease to it; media flick-skips. */
    public void edgeSlideTo(float level) {
        level = clamp(level, 0f, 1f);
        if (page == MEDIA) {
            if (host == null) return;
            if (!edgeStarted) { edgeStarted = true; lastEdgeLevel = level; edgeSkipAccum = 0f; return; }
            edgeSkipAccum += (level - lastEdgeLevel);   // up = positive
            lastEdgeLevel = level;
            if (edgeSkipAccum > 0.18f) { hap(); host.mediaNext(); edgeSkipAccum = 0f; }
            else if (edgeSkipAccum < -0.18f) { hap(); host.mediaPrev(); edgeSkipAccum = 0f; }
        } else {
            edgeStarted = true;
            setTarget(level);
        }
    }

    public void edgeSlideEnd() {
        edgeStarted = false;
        edgeSkipAccum = 0f;
        stopSlider();
    }

    /** Called on finger up: freeze the bar exactly where it is — no drift past the release point. */
    private void stopSlider() {
        sliderActive = false;
        removeCallbacks(slider);
    }

    private float currentLevel(int pg) {
        if (pg == VOLUME) return volLevel();
        if (pg == BRIGHT) return readBrightness() / 255f;
        return 0f;
    }

    private void applyLevel(int pg, float level) {
        level = clamp(level, 0f, 1f);
        if (pg == VOLUME && am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = Math.round(level * max);
            if (v != am.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                hap();                               // tick as each step is reached
            }
        } else if (pg == BRIGHT) {
            writeBrightness(Math.round(clamp(level * 255, 6, 255)));
        }
    }

    /** Per-frame smoothing: the bar glides toward the finger (frame-rate independent ease). Runs only while pressed. */
    private final Runnable slider = new Runnable() {
        @Override public void run() {
            if (!sliderActive) return;
            long now = SystemClock.uptimeMillis();
            float dt = (now - sliderLast) / 1000f; sliderLast = now;
            if (dt > 0.05f) dt = 0.05f;
            float k = 1f - (float) Math.exp(-dt / SLIDER_TAU);
            displayLevel += (fingerTarget - displayLevel) * k;
            applyLevel(sliderPage, displayLevel);
            invalidate();
            postDelayed(this, 16);
        }
    };

    private void adjust(float dyUpPixels) {
        float frac = dyUpPixels / (getHeight() * 0.55f);
        if (page == VOLUME && am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int before = Math.round(volF);
            volF = clamp(volF + frac * max, 0, max);
            int after = Math.round(volF);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, after, 0);
            if (after != before) hap();                 // notched tick per volume step
        } else if (page == BRIGHT) {
            brightF = clamp(brightF + frac * 255, 6, 255);
            writeBrightness(Math.round(brightF));        // continuous — no per-step buzz
        } else if (page == MEDIA && host != null) {
            skipAccum += dyUpPixels;
            if (skipAccum > dp(55)) { hap(); host.mediaNext(); skipAccum = 0; }
            else if (skipAccum < -dp(55)) { hap(); host.mediaPrev(); skipAccum = 0; }
        }
    }

    private void settlePage() {
        int w = getWidth();
        int dir = 0;                                   // one-way carousel: only left-to-right rotates
        if (pageOffset > w * 0.25f) dir = -1;          // left-to-right swipe -> rotate; right-to-left ignored
        final int target = dir == -1 ? (page + 2) % 3 : page;
        if (dir != 0) hap();                            // tick on page change
        float to = dir == 1 ? -w : (dir == -1 ? w : 0);
        animating = true;
        ValueAnimator a = ValueAnimator.ofFloat(pageOffset, to);
        a.setDuration(190);
        a.addUpdateListener(an -> { pageOffset = (float) an.getAnimatedValue(); invalidate(); });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                page = target; pageOffset = 0; animating = false; invalidate();
            }
        });
        a.start();
    }

    // ---- drawing --------------------------------------------------------------

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth();
        if (w <= 0) return;
        // each page's opacity tracks its horizontal position: a page is faded while off to the
        // left/right and gains full colour as it slides into the centre (cross-fade on swipe).
        drawPageFaded(cv, (page + 2) % 3, -w + pageOffset);   // previous (wraps)
        drawPageFaded(cv, page, pageOffset);
        drawPageFaded(cv, (page + 1) % 3, w + pageOffset);    // next (wraps)
    }

    private void drawPageFaded(Canvas cv, int idx, float off) {
        int w = getWidth();
        float a = 1f - Math.min(1f, Math.abs(off) / (float) w);
        if (a <= 0.02f) return;                                  // off-screen / fully faded
        if (a >= 0.99f) { drawPage(cv, idx, off); return; }
        int sc = cv.saveLayerAlpha(0f, 0f, w, getHeight(), Math.round(a * 255f));
        drawPage(cv, idx, off);
        cv.restoreToCount(sc);
    }

    private void drawPage(Canvas cv, int idx, float dx) {
        float cx = getWidth() - dp(43) + dx;   // rail anchored to the right edge (widget is full-width)
        float h = getHeight();
        float top = dp(16);
        if (idx == MEDIA) { drawMedia(cv, cx); return; }

        boolean vol = idx == VOLUME;
        // while sliding, draw the smooth float so the coarse 5-step volume doesn't make the bar jump
        float level = (sliderActive && idx == sliderPage)
                ? displayLevel
                : (vol ? volLevel() : (readBrightness() / 255f));
        drawIcon(cv, cx, top + dp(26), vol);

        // vertical level bar — symmetric: equal gap from the icon above and the % below
        barTop = top + dp(48); barBot = h - dp(38);
        float barW = dp(26), bx = cx - barW / 2f;
        float rad = barW / 2f;
        r.set(bx, barTop, bx + barW, barBot);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0xFF555555);
        cv.drawRoundRect(r, rad, rad, p);
        // fill, clipped to the track's pill so it never deforms or pokes outside at low levels
        float fillTop = barBot - (barBot - barTop) * clamp(level, 0, 1);
        if (fillTop < barBot - 0.5f) {
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            float fr = Math.min(rad, (barBot - fillTop) / 2f);   // valid corner radius for short fills
            r.set(bx, fillTop, bx + barW, barBot);
            cv.drawRoundRect(r, fr, fr, p);
            cv.restore();
            r.set(bx, barTop, bx + barW, barBot);   // restore r for any later use
        }

        tp.setTextSize(sp(20));
        cv.drawText(Math.round(level * 100) + "%", cx, h - dp(15), tp);
    }

    private void drawMedia(Canvas cv, float cx) {
        float cy = getHeight() / 2f, gap = dp(66);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL);
        drawSkip(cv, cx, cy - gap, false);   // previous (top)
        // play / pause (centre — lines up with the clock)
        float s = dp(27);
        if (playing) {
            r.set(cx - s * 0.72f, cy - s, cx - s * 0.16f, cy + s);
            cv.drawRoundRect(r, dp(3), dp(3), p);
            r.set(cx + s * 0.16f, cy - s, cx + s * 0.72f, cy + s);
            cv.drawRoundRect(r, dp(3), dp(3), p);
        } else {
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(cx - s * 0.55f, cy - s);
            tri.lineTo(cx + s * 0.85f, cy);
            tri.lineTo(cx - s * 0.55f, cy + s);
            tri.close();
            cv.drawPath(tri, p);
        }
        drawSkip(cv, cx, cy + gap, true);    // next (bottom)
    }

    /** Double-triangle skip glyph; next points right, previous points left. */
    private void drawSkip(Canvas cv, float cx, float cy, boolean next) {
        float s = dp(12);
        int sign = next ? 1 : -1;
        for (int k = 0; k < 2; k++) {
            float ox = cx + sign * (k * s * 1.05f - s * 0.5f);
            android.graphics.Path t = new android.graphics.Path();
            t.moveTo(ox - sign * s * 0.55f, cy - s);
            t.lineTo(ox + sign * s * 0.6f, cy);
            t.lineTo(ox - sign * s * 0.55f, cy + s);
            t.close();
            cv.drawPath(t, p);
        }
    }

    private void drawIcon(Canvas cv, float cx, float cy, boolean speaker) {
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL);
        if (speaker) {
            float s = dp(11);
            android.graphics.Path sp = new android.graphics.Path();
            sp.moveTo(cx - s, cy - s * 0.4f);
            sp.lineTo(cx - s * 0.3f, cy - s * 0.4f);
            sp.lineTo(cx + s * 0.4f, cy - s);
            sp.lineTo(cx + s * 0.4f, cy + s);
            sp.lineTo(cx - s * 0.3f, cy + s * 0.4f);
            sp.lineTo(cx - s, cy + s * 0.4f);
            sp.close();
            cv.drawPath(sp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            cv.drawArc(cx + s * 0.2f, cy - s, cx + s * 1.6f, cy + s, -55, 110, false, p);
        } else { // sun
            cv.drawCircle(cx, cy, dp(7), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            for (int i = 0; i < 8; i++) {
                double ang = Math.PI * i / 4;
                float r1 = dp(11), r2 = dp(15);
                cv.drawLine(cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1,
                        cx + (float) Math.cos(ang) * r2, cy + (float) Math.sin(ang) * r2, p);
            }
        }
    }

    private float volLevel() {
        if (am == null) return 0;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max == 0 ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private int readBrightness() {
        try { return Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS); }
        catch (Exception e) { return 128; }
    }

    private void writeBrightness(int v) {
        try {
            if (Settings.System.canWrite(getContext())) {
                Settings.System.putInt(getContext().getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
            }
        } catch (Exception ignored) {}
    }

    private float sp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics()); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
