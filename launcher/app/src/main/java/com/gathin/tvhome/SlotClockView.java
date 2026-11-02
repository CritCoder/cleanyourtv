package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextClock;

/**
 * The home clock with a slot-machine roll: when a digit changes, the old one slides up and out while the
 * new one rises in from below, each changed slot starting a beat after the one before it. Format, locale
 * and the minute ticks are all inherited from {@link TextClock} (so ClockFormat.apply treats it as any
 * other clock); while no roll is running it draws exactly as a TextView would.
 */
public class SlotClockView extends TextClock {
    private static final long ROLL_MS = 500;
    private static final long STAGGER_MS = 55;
    /** How far a digit travels, as a share of the line height. */
    private static final float TRAVEL = 0.85f;

    /** What is on screen now, and what rolls out while an animation runs; no initializers, because
     *  TextClock's constructor calls onTextChanged before this class's initializers would run. */
    private String shown;
    private String previous;
    private ValueAnimator roll;
    private long totalMs;
    private DecelerateInterpolator ease;

    public SlotClockView(Context context) { super(context); }
    public SlotClockView(Context context, AttributeSet attrs) { super(context, attrs); }
    public SlotClockView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        String now = text == null ? "" : text.toString();
        if (now.equals(shown)) return;
        // The first fill, a format change, or a tick while Home is not on screen: show it with no roll.
        if (shown == null || !isAttachedToWindow() || getWindowVisibility() != View.VISIBLE
                || !isShown() || getWidth() == 0) {
            shown = now;
            if (roll != null) roll.cancel();
            return;
        }
        previous = shown;
        shown = now;
        startRoll();
    }

    private void startRoll() {
        if (roll != null) roll.cancel();
        if (ease == null) ease = new DecelerateInterpolator(1.5f);
        totalMs = ROLL_MS + STAGGER_MS * Math.max(0, changedSlots() - 1);
        roll = ValueAnimator.ofFloat(0f, 1f);
        roll.setDuration(totalMs);
        roll.addUpdateListener(a -> invalidate());
        roll.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                previous = null;
                invalidate();
            }
        });
        roll.start();
    }

    /** Slots compared from the right, so the minutes line up when the string grows (9:59 to 10:00). */
    private int changedSlots() {
        int n = Math.max(shown.length(), previous.length());
        int changed = 0;
        for (int k = 0; k < n; k++) {
            if (charFromRight(shown, k) != charFromRight(previous, k)) changed++;
        }
        return Math.max(1, changed);
    }

    private static char charFromRight(String s, int k) {
        int i = s.length() - 1 - k;
        return i >= 0 ? s.charAt(i) : '\0';
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (roll == null || !roll.isRunning() || previous == null || shown == null) {
            super.onDraw(canvas);
            return;
        }
        Paint paint = getPaint();
        int savedColor = paint.getColor();
        int color = getCurrentTextColor();
        paint.setColor(color);
        int baseAlpha = Color.alpha(color);
        float baseline = getBaseline();
        float left = getCompoundPaddingLeft();
        float line = paint.descent() - paint.ascent();
        float elapsed = roll.getAnimatedFraction() * totalMs;

        // Digits appear and vanish at the text line's edges, like drums behind a window.
        canvas.save();
        canvas.clipRect(0f, baseline + paint.ascent() - 2f, getWidth(), baseline + paint.descent() + 2f);
        int n = Math.max(shown.length(), previous.length());
        int staggerIndex = 0;
        for (int k = n - 1; k >= 0; k--) {   // left to right
            char cur = charFromRight(shown, k);
            char old = charFromRight(previous, k);
            boolean change = cur != old;
            float t = 1f;
            if (change) {
                t = Math.max(0f, Math.min(1f, (elapsed - staggerIndex * STAGGER_MS) / (float) ROLL_MS));
                staggerIndex++;
            }
            float e = ease.getInterpolation(t);
            if (cur != '\0') {
                int i = shown.length() - 1 - k;
                float x = left + paint.measureText(shown, 0, i);
                if (!change || t >= 1f) {
                    paint.setAlpha(baseAlpha);
                    canvas.drawText(shown, i, i + 1, x, baseline, paint);
                } else {
                    paint.setAlpha((int) (baseAlpha * Math.min(1f, 0.15f + 0.85f * e)));
                    canvas.drawText(shown, i, i + 1, x, baseline + (1f - e) * line * TRAVEL, paint);
                }
            }
            if (change && old != '\0' && t < 1f) {
                int j = previous.length() - 1 - k;
                float xo = left + paint.measureText(previous, 0, j);
                paint.setAlpha((int) (baseAlpha * (1f - e)));
                canvas.drawText(previous, j, j + 1, xo, baseline - e * line * TRAVEL, paint);
            }
        }
        canvas.restore();
        paint.setAlpha(baseAlpha);
        paint.setColor(savedColor);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (roll != null) roll.cancel();
        super.onDetachedFromWindow();
    }
}
