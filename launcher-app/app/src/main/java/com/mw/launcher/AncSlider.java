package com.mw.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vertical 3-mode wheel — Standard / ANC / Ambient — placed on the right edge like
 * the native IKKO sound-profile widget. The current mode sits centred with its
 * neighbours peeking above/below; swipe up = next, down = previous. Reuses the
 * original mode_*.webp icons and offbit dot font. Indices: 0 Standard, 1 ANC, 2 Ambient.
 */
public class AncSlider extends View {

    public interface OnModeChange { void onMode(int mode); }

    private final float density;
    private final Bitmap[] icons = new Bitmap[3];
    private final String[] labels = {"Standard", "ANC", "Ambient"};
    private final TextPaint text;
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private int mode = 0;
    private float dragStartY, offsetY;
    private boolean animating;
    private OnModeChange listener;

    public AncSlider(Context c, Typeface font) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        icons[0] = BitmapFactory.decodeResource(getResources(), R.drawable.mode_standard);
        icons[1] = BitmapFactory.decodeResource(getResources(), R.drawable.mode_anc);
        icons[2] = BitmapFactory.decodeResource(getResources(), R.drawable.mode_ambient);
        text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(font);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    public void setOnModeChange(OnModeChange l) { listener = l; }

    public void setMode(int m) { mode = ((m % 3) + 3) % 3; invalidate(); }

    public int getMode() { return mode; }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (animating) return true;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragStartY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                offsetY += e.getY() - dragStartY;
                dragStartY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                settle();
                return true;
        }
        return true;
    }

    private void settle() {
        int h = getHeight();
        float threshold = h * 0.16f;
        final int newMode;
        final float target;
        if (offsetY > threshold) {            // dragged down -> previous
            target = h; newMode = (mode + 2) % 3;
        } else if (offsetY < -threshold) {     // dragged up -> next
            target = -h; newMode = (mode + 1) % 3;
        } else {
            target = 0; newMode = mode;
        }
        animating = true;
        ValueAnimator a = ValueAnimator.ofFloat(offsetY, target);
        a.setDuration(200);
        a.addUpdateListener(an -> { offsetY = (float) an.getAnimatedValue(); invalidate(); });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                offsetY = 0;
                animating = false;
                boolean changed = newMode != mode;
                mode = newMode;
                invalidate();
                if (changed && listener != null) listener.onMode(mode);
            }
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int h = getHeight();
        if (h <= 0) return;
        canvas.save();
        canvas.translate(0, offsetY);
        drawItem(canvas, (mode + 2) % 3, h / 2f - h, 0.30f);  // previous (above)
        drawItem(canvas, mode, h / 2f, 1f);                    // current (centre)
        drawItem(canvas, (mode + 1) % 3, h / 2f + h, 0.30f);   // next (below)
        canvas.restore();
    }

    private void drawItem(Canvas canvas, int i, float cy, float alpha) {
        Bitmap bmp = icons[i];
        float cx = getWidth() / 2f;
        int a = (int) (alpha * 255);
        if (bmp != null) {
            float ih = dp(28);
            float iw = bmp.getWidth() * (ih / bmp.getHeight());
            float top = cy - dp(22);
            iconPaint.setAlpha(a);
            Rect src = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
            RectF dst = new RectF(cx - iw / 2f, top, cx + iw / 2f, top + ih);
            canvas.drawBitmap(bmp, src, dst, iconPaint);
        }
        text.setAlpha(a);
        canvas.drawText(labels[i], cx, cy + dp(22), text);
    }
}
