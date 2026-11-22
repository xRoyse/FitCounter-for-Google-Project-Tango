package com.example.fitcounter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

public final class SkeletonView extends View {
    private static final int[] BONES = {5, 6, 5, 7, 7, 9, 6, 8, 8, 10, 5, 11, 6, 12,
            11, 12, 11, 13, 13, 15, 12, 14, 14, 16};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect bounds = new Rect();
    private final float density;
    private PoseReading reading;
    private Bitmap image;
    private boolean mirror;
    private long lastDrawnFrame, firstDraw;
    private final long[] drawTimes = new long[60], frameAges = new long[60];
    private int drawn;

    public SkeletonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    void show(PoseReading reading, Bitmap image, boolean mirror) {
        if (this.reading == reading && this.mirror == mirror) return;
        this.reading = reading;
        this.image = image;
        this.mirror = mirror;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (reading == null) return;
        long start = System.nanoTime();
        if (image != null) {
            bounds.set(0, 0, getWidth(), getHeight());
            int saved = canvas.save();
            if (mirror) canvas.scale(-1, 1, getWidth() * 0.5f, 0);
            canvas.drawBitmap(image, null, bounds, imagePaint);
            canvas.restoreToCount(saved);
        }
        Pose pose = reading.pose;
        if (pose == null) return;
        paint.setColor(0xff69f0ae);
        paint.setStrokeWidth(2.5f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < BONES.length; i += 2) {
            int a = BONES[i], b = BONES[i + 1];
            if (pose.visible(a, PoseValidator.DRAW_CONFIDENCE) && pose.visible(b, PoseValidator.DRAW_CONFIDENCE)) {
                canvas.drawLine(x(a), y(a), x(b), y(b), paint);
            }
        }
        paint.setColor(0xffffffff);
        for (int i = 5; i < Pose.SIZE; i++) {
            if (pose.visible(i, PoseValidator.DRAW_CONFIDENCE)) {
                canvas.drawCircle(x(i), y(i), 3.2f * density, paint);
            }
        }
        if (BuildConfig.DEBUG && reading.frameId > lastDrawnFrame) {
            lastDrawnFrame = reading.frameId;
            long now = System.nanoTime();
            if (drawn == 0) firstDraw = now;
            drawTimes[drawn] = now - start;
            frameAges[drawn] = now - reading.arrivalNanos;
            if (++drawn == drawTimes.length) {
                java.util.Arrays.sort(drawTimes);
                java.util.Arrays.sort(frameAges);
                android.util.Log.d("FitCounter", "Overlay fps=" + 59e9 / Math.max(1, now - firstDraw)
                        + " draw ms p50/p95=" + drawTimes[29] / 1e6 + "/" + drawTimes[56] / 1e6
                        + " callback-to-draw ms p50/p95=" + frameAges[29] / 1e6 + "/" + frameAges[56] / 1e6);
                drawn = 0;
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
