package com.example.fitcounter;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.util.Arrays;
import java.util.ArrayDeque;

final class PosePipeline {
    static final long MAX_DISPLAY_AGE_MS = 350;
    interface Listener {
        void onReady();
        default void onMeasurement(PoseReading reading) {}
        void onPose(PoseReading reading, Bitmap image);
        void onModelError();
    }

    private final HandlerThread thread = new HandlerThread("fitcounter-pose");
    private final Handler worker;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final PairedPreview preview = new PairedPreview();
    private final Runnable deliver = this::deliverLatest;
    private final ArrayDeque<PoseReading> measurements = new ArrayDeque<>();
    private final Diagnostics diagnostics;

    private boolean closed, failed, queued;
    private Frame pending;
    private Bitmap latestImage;
    private boolean deliveryQueued;
    private long lastOffered;
    private long lastConfirmed = -1;
    private PoseEstimator estimator;
    private PoseAnalyzer analyzer;
    private final long[][] timing = new long[8][60];
    private static final String[] STAGES = {"queue", "preprocess", "inference", "postprocess",
            "smoothing", "angles", "preview-copy", "callback-to-result"};
    private int runs;
    private long windowStart, windowFrame;

    PosePipeline(Context context, Listener listener) {
        this.listener = listener;
        Context app = context.getApplicationContext();
        diagnostics = Diagnostics.get(app);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                estimator = PoseEstimator.open(app);
                analyzer = new PoseAnalyzer(estimator);
                diagnostics.log("INFO", "MODEL", "MODEL_READY", estimator.getClass().getSimpleName());
                notifyUi(listener::onReady);
            } catch (Exception | LinkageError e) {
                fail(e);
            }
        });
    }

    void offer(Frame frame) {
        Frame discard;
        synchronized (this) {
            if (closed || failed || frame.id <= lastOffered) {
                discard = frame;
            } else {
                lastOffered = frame.id;
                discard = pending;
                pending = frame;
                if (!queued) {
                    queued = true;
                    worker.post(this::process);
                }
            }
        }
        if (discard != null) discard.release();
        diagnostics.offered(frame, discard != null, inQueue());
    }

    private void process() {
        Frame frame;
        synchronized (this) {
            if (closed || failed) return;
            frame = pending;
            pending = null;
            queued = false;
        }
        if (frame == null) return;
        diagnostics.processing(inQueue());
        try {
            PoseReading reading = analyzer.analyze(frame);
            long copyStart = System.nanoTime();
            if (reading.pose != null) lastConfirmed = reading.time;
            // При кратком пропуске сохраняем тот же режим показа, убирая только скелет.
            Bitmap image = lastConfirmed >= 0 && reading.time - lastConfirmed <= MAX_DISPLAY_AGE_MS
                    && (System.nanoTime() - reading.arrivalNanos) / 1_000_000 < MAX_DISPLAY_AGE_MS
                    ? preview.copy(frame, reading.transform) : null;
            long copy = image == null ? 0 : System.nanoTime() - copyStart;
            if (BuildConfig.DEBUG) logTiming(reading, copy);
            diagnostics.processed(reading, copy);
            synchronized (this) {
                if (closed) return;
                // Измерения фаз идут по порядку. В UI рисуется только последняя пара кадра и позы.
                if (measurements.size() == 16) {
                    measurements.removeFirst();
                    diagnostics.measurementDropped();
                }
                measurements.addLast(reading);
                latestImage = image;
                if (!deliveryQueued) {
                    deliveryQueued = true;
                    ui.post(deliver);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        } finally {
            frame.release();
        }
    }

    private void deliverLatest() {
        PoseReading[] batch;
        Bitmap image;
        synchronized (this) {
            batch = measurements.toArray(new PoseReading[0]);
            measurements.clear();
            image = latestImage;
            latestImage = null;
            deliveryQueued = false;
            if (closed || failed) return;
        }
        if (batch.length == 0) return;
        for (PoseReading reading : batch) listener.onMeasurement(reading);
        listener.onPose(batch[batch.length - 1], image);
    }

    private void logTiming(PoseReading r, long copyNanos) {
        long now = System.nanoTime();
        if (runs == 0) {
            windowStart = r.arrivalNanos;
            windowFrame = r.frameId;
        }
        timing[0][runs] = r.queueNanos;
        timing[1][runs] = r.preprocessNanos;
        timing[2][runs] = r.inferenceNanos;
        timing[3][runs] = r.postprocessNanos;
        timing[4][runs] = r.filterNanos;
        timing[5][runs] = r.angleNanos;
        timing[6][runs] = copyNanos;
        timing[7][runs] = now - r.arrivalNanos;
        if (++runs == timing[0].length) {
            double seconds = Math.max(1, r.arrivalNanos - windowStart) / 1e9;
            StringBuilder line = new StringBuilder("Input ").append(r.transform.width).append('x')
                    .append(r.transform.height).append(" camera callback fps=")
                    .append((r.frameId - windowFrame) / seconds)
                    .append(" processed fps=").append((runs - 1) / seconds)
                    .append(" ms p50/p95");
            for (int i = 0; i < timing.length; i++) {
                Arrays.sort(timing[i]);
                line.append(' ').append(STAGES[i]).append('=')
                        .append(timing[i][29] / 1000 / 1000.0).append('/')
                        .append(timing[i][56] / 1000 / 1000.0);
            }
            Log.d("FitCounter", line.toString());
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
            measurements.clear();
            latestImage = null;
            discard = pending;
            pending = null;
        }
        if (discard != null) discard.release();
        diagnostics.pipelineStopped();
        Log.e("FitCounter", "Pose model failure", e);
        diagnostics.error("MODEL", "MODEL_FAILURE", e);
        notifyUi(listener::onModelError);
    }

    void close() {
        Frame discard;
        synchronized (this) {
            if (closed) return;
            closed = true;
            measurements.clear();
            latestImage = null;
            ui.removeCallbacks(deliver);
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
        diagnostics.pipelineStopped();
    }

    private synchronized int inQueue() { return pending == null ? 0 : 1; }
}
