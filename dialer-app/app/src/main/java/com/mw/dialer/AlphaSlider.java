package com.mw.dialer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class AlphaSlider extends View {

    private static final String[] LETTERS = {
        "#","A","B","C","D","E","F","G","H","I","J","K","L","M",
        "N","O","P","Q","R","S","T","U","V","W","X","Y","Z"
    };

    // Arc letters: gentle parabola
    private static final float ARC_BASE_SP  = 9f;   // resting (no touch)
    private static final float ARC_MIN_SP   = 6f;   // far from focus while touching
    private static final float ARC_MAX_SP   = 15f;  // adjacent to focus
    private static final float ARC_SIGMA    = 3.8f; // wide, smooth curve
    private static final float ARC_BULGE_DP = 42f;  // how far arc bows left

    // Bubble at peak
    private static final float BUBBLE_R_DP  = 26f;  // circle radius
    private static final float BUBBLE_SP    = 30f;  // letter size inside bubble
    // Clear gap between the bubble's right edge and the arc's leftmost reach
    private static final float BUBBLE_GAP_DP = 18f;

    private final Paint arcPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float touchY      = -1f;
    private int   activeIndex = -1;
    private float dp;

    public interface Listener { void onSelect(String letter, boolean done); }
    private Listener listener;

    public AlphaSlider(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = ctx.getResources().getDisplayMetrics().density;

        arcPaint.setColor(Color.WHITE);
        arcPaint.setTextAlign(Paint.Align.RIGHT);

        circlePaint.setColor(0xFF1c1c1c);  // dark circle fill
        circlePaint.setStyle(Paint.Style.FILL);

        bubblePaint.setColor(Color.WHITE);
        bubblePaint.setTextAlign(Paint.Align.CENTER);

        setClickable(true);
    }

    public void setListener(Listener l) { listener = l; }

    @Override
    protected void onDraw(Canvas canvas) {
        int   n     = LETTERS.length;
        float h     = getHeight();
        float w     = getWidth();
        float slotH = h / n;

        boolean touching = touchY >= 0;

        // ── 1. Draw all arc letters ────────────────────────────────────────
        for (int i = 0; i < n; i++) {
            float cy = (i + 0.5f) * slotH;

            float sizePx, bulge;
            int alpha;

            if (!touching) {
                sizePx = ARC_BASE_SP * dp;
                bulge  = 0f;
                alpha  = 110;
            } else {
                float dist   = Math.abs(cy - touchY) / slotH;
                float factor = gauss(dist, ARC_SIGMA);

                sizePx = (ARC_MIN_SP + (ARC_MAX_SP - ARC_MIN_SP) * factor) * dp;
                bulge  = ARC_BULGE_DP * dp * factor;
                alpha  = (int)(60 + 140 * factor);
            }

            arcPaint.setTextSize(sizePx);
            arcPaint.setAlpha(alpha);

            float x        = w - 2 * dp - bulge;
            float baseline = cy + sizePx * 0.36f;
            canvas.drawText(LETTERS[i], x, baseline, arcPaint);
        }

        // ── 2. Draw bubble circle at the arc peak ─────────────────────────
        if (touching && activeIndex >= 0) {
            float peakCY  = (activeIndex + 0.5f) * slotH;
            float bubbleR = BUBBLE_R_DP * dp;

            // Leftmost reach of the arc letters near the peak:
            //   right edge of peak letter = w - 2 - ARC_BULGE, then minus the letter's own width.
            float arcTipRight = w - 2 * dp - ARC_BULGE_DP * dp;
            float arcLetterW  = ARC_MAX_SP * dp * 0.7f;           // approx glyph width
            float arcLeftMost = arcTipRight - arcLetterW;

            // Bubble's RIGHT edge sits a clear gap to the left of the arc.
            float bubbleRightEdge = arcLeftMost - BUBBLE_GAP_DP * dp;
            float cx = bubbleRightEdge - bubbleR;
            float cy = peakCY;

            canvas.drawCircle(cx, cy, bubbleR, circlePaint);

            bubblePaint.setTextSize(BUBBLE_SP * dp);
            bubblePaint.setAlpha(255);
            float textBaseline = cy + (BUBBLE_SP * dp) * 0.36f;
            canvas.drawText(LETTERS[activeIndex], cx, textBaseline, bubblePaint);
        }
    }

    private static float gauss(float d, float sigma) {
        return (float) Math.exp(-(d * d) / (2f * sigma * sigma));
    }

    private static final float TOUCH_ZONE_DP = 44f;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int   action    = e.getAction();
        float touchZone = TOUCH_ZONE_DP * dp;

        if (action == MotionEvent.ACTION_DOWN && e.getX() < getWidth() - touchZone) {
            return false;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            touchY = Math.max(0, Math.min(e.getY(), getHeight()));
            float slotH = (float) getHeight() / LETTERS.length;
            int idx = Math.max(0, Math.min((int)(touchY / slotH), LETTERS.length - 1));
            if (idx != activeIndex) {
                activeIndex = idx;
                if (listener != null) listener.onSelect(LETTERS[idx], false);
            }
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchY = -1f;
            if (listener != null && activeIndex >= 0)
                listener.onSelect(LETTERS[activeIndex], true);
            activeIndex = -1;
            invalidate();
            return true;
        }

        return false;
    }
}
