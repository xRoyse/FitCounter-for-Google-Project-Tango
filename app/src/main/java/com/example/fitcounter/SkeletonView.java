package com.example.fitcounter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public final class SkeletonView extends View {
    private static final int[] BONES = {5, 6, 5, 7, 7, 9, 6, 8, 8, 10, 5, 11, 6, 12,
            11, 12, 11, 13, 13, 15, 12, 14, 14, 16};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private PoseReading reading;
    private boolean mirror;

    public SkeletonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    void show(PoseReading reading, boolean mirror) {
        this.reading = reading;
        this.mirror = mirror;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (reading == null) return;
        Pose pose = reading.pose;
        paint.setColor(0xff69f0ae);
        paint.setStrokeWidth(2.5f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < BONES.length; i += 2) {
            int a = BONES[i], b = BONES[i + 1];
            if (pose.visible(a, PoseFilter.DRAW_CONFIDENCE) && pose.visible(b, PoseFilter.DRAW_CONFIDENCE)) {
                canvas.drawLine(x(a), y(a), x(b), y(b), paint);
            }
        }
        paint.setColor(0xffffffff);
        for (int i = 5; i < Pose.SIZE; i++) {
            if (pose.visible(i, PoseFilter.DRAW_CONFIDENCE)) {
                canvas.drawCircle(x(i), y(i), 3.2f * density, paint);
            }
        }
    }

    private float x(int joint) {
        float fraction = reading.pose.x[joint] / reading.transform.uprightWidth;
        return (mirror ? 1 - fraction : fraction) * getWidth();
    }

    private float y(int joint) {
        return reading.pose.y[joint] / reading.transform.uprightHeight * getHeight();
    }
}
