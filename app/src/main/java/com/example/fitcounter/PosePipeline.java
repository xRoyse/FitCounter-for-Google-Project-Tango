package com.example.fitcounter;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.Arrays;

final class PosePipeline {
    interface Listener {
        void onReady();
        void onPose(PoseReading reading);
        void onModelError();
    }

    private static final long MIN_INTERVAL_MS = 120;
    private final HandlerThread thread = new HandlerThread("fitcounter-pose");
    private final Handler worker;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final PoseFilter filter = new PoseFilter();

    private boolean closed, failed, queued;
    private Frame pending;
    private PoseEstimator estimator;
    private FrameTransform transform;
    private long lastRun = -MIN_INTERVAL_MS;
    private final long[] inferenceTimes = new long[60];
    private final long[] frameAges = new long[60];
    private int runs;
    private long windowStart;

    PosePipeline(Context context, Listener listener) {
        this.listener = listener;
        Context app = context.getApplicationContext();
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                estimator = PoseEstimator.open(app);
                notifyUi(listener::onReady);
            } catch (Exception | LinkageError e) {
                fail(e);
            }
        });
    }

    void offer(Frame frame) {
        Frame discard;
        synchronized (this) {
            if (closed || failed) {
                discard = frame;
            } else {
                discard = pending;
                pending = frame;
                if (!queued) {
                    queued = true;
                    worker.postDelayed(this::process,
                            Math.max(0, MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastRun)));
                }
            }
        }
        if (discard != null) discard.release();
    }

    private void process() {
        Frame frame;
        synchronized (this) {
            if (closed || failed) return;
            frame = pending;
            pending = null;
            queued = false;
            lastRun = SystemClock.uptimeMillis();
        }
        if (frame == null) return;
        try {
            if (transform == null || transform.rotation != frame.rotation
                    || transform.width != frame.width || transform.height != frame.height) {
                transform = new FrameTransform(frame.width, frame.height, frame.rotation, estimator.size());
            }
            Pose pose = filter.apply(estimator.estimate(frame, transform), transform, frame.time);
            PoseReading reading = new PoseReading(pose, transform, frame.time);
            if (BuildConfig.DEBUG) logTiming(frame);
            notifyUi(() -> listener.onPose(reading));
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        } finally {
            frame.release();
        }
    }

    private void logTiming(Frame frame) {
        long now = SystemClock.uptimeMillis();
        if (runs == 0) windowStart = now;
        inferenceTimes[runs] = estimator.inferenceNanos() / 1_000_000;
        frameAges[runs] = now - frame.time;
        if (++runs == inferenceTimes.length) {
            Arrays.sort(inferenceTimes);
            Arrays.sort(frameAges);
            Log.d("FitCounter", "Input " + frame.width + "x" + frame.height
                    + ", processed fps=" + 1000f * (runs - 1) / Math.max(1, now - windowStart)
                    + ", inference ms p50/p95=" + inferenceTimes[29] + "/" + inferenceTimes[56]
                    + ", frame age ms p50/p95=" + frameAges[29] + "/" + frameAges[56]);
            runs = 0;
        }
    }

    private void notifyUi(Runnable callback) {
        ui.post(() -> {
            synchronized (this) {
                if (closed) return;
            }
            callback.run();
        });
    }

    private void fail(Throwable e) {
        Frame discard;
        synchronized (this) {
            failed = true;
            discard = pending;
            pending = null;
        }
        if (discard != null) discard.release();
        Log.e("FitCounter", "Pose model failure", e);
        notifyUi(listener::onModelError);
    }

    void close() {
        Frame discard;
        synchronized (this) {
            if (closed) return;
            closed = true;
            discard = pending;
            pending = null;
            worker.removeCallbacksAndMessages(null);
            worker.post(() -> {
                try {
                    if (estimator != null) estimator.close();
                } finally {
                    thread.quitSafely();
                }
            });
        }
        if (discard != null) discard.release();
    }
}
