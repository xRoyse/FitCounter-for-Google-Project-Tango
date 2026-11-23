package com.example.fitcounter;

import android.os.SystemClock;
import java.util.Arrays;

final class DiagnosticsMetrics {
    private final Rate camera = new Rate(), model = new Rate(), overlay = new Rate(), preview = new Rate();
    private final long[] inference = new long[90], latency = new long[90];
    private int inferred, rendered;
    private long received, dropped, processed, forwarded, deliveryDrops, stale, rejectedPoses, lastFrame;
    private long lastCallback, preprocess, postprocess, filter, angles, copy, toInference, draw, drawAt;
    private long uiDelay, lastStall, updateCost;
    private int queue;
    private boolean busy;

    synchronized void offer(Frame frame, boolean discarded, int queue) {
        camera.add(frame.time);
        received++;
        lastCallback = frame.time;
        if (discarded) dropped++;
        this.queue = queue;
    }

    synchronized void start(int queue) {
        forwarded++;
        busy = true;
        this.queue = queue;
    }

    synchronized void finish(PoseReading r, long copied) {
        model.add(SystemClock.uptimeMillis());
        processed++;
        lastFrame = r.frameId;
        if (r.pose == null) rejectedPoses++;
        inference[inferred++ % inference.length] = r.inferenceNanos;
        preprocess = r.preprocessNanos;
        postprocess = r.postprocessNanos;
        filter = r.filterNanos;
        angles = r.angleNanos;
        copy = copied;
        toInference = r.queueNanos + r.preprocessNanos;
        busy = false;
    }

    synchronized void render(long duration, long age) {
        overlay.add(SystemClock.uptimeMillis());
        draw = duration;
        drawAt = System.nanoTime();
        latency[rendered++ % latency.length] = age;
    }

    synchronized void uiPreview() { preview.add(SystemClock.uptimeMillis()); }
    synchronized void droppedMeasurement() { deliveryDrops++; }
    synchronized void staleOverlay() { stale++; }
    synchronized void loggingCost(long nanos) { updateCost = nanos; }
    synchronized void heartbeat(long delay) {
        uiDelay = delay;
        if (delay >= 100) lastStall = delay;
    }

    synchronized void stopped() { busy = false; queue = 0; }

    synchronized void reset() {
        camera.clear();
        model.clear();
        overlay.clear();
        preview.clear();
        inferred = rendered = queue = 0;
        received = dropped = processed = forwarded = deliveryDrops = stale = rejectedPoses = lastFrame = 0;
        lastCallback = preprocess = postprocess = filter = angles = copy = toInference = draw = drawAt = 0;
        uiDelay = lastStall = updateCost = 0;
        busy = false;
    }

    synchronized Data snapshot() {
        long now = SystemClock.uptimeMillis();
        return new Data(camera.fps(now), model.fps(now), overlay.fps(now), preview.fps(now),
                received, dropped, processed, forwarded, deliveryDrops, stale, rejectedPoses, lastFrame,
                lastCallback, preprocess, postprocess, filter, angles, copy, toInference, draw,
                drawAt == 0 ? -1 : System.nanoTime() - drawAt + latency[(rendered - 1) % latency.length],
                uiDelay, lastStall, updateCost, queue, busy,
                new Sample(inference, inferred), new Sample(latency, rendered));
    }

    static final class Data {
        final double camera, model, overlay, preview;
        final long received, dropped, processed, forwarded, deliveryDrops, stale, rejectedPoses, lastFrame;
        final long lastCallback, preprocess, postprocess, filter, angles, copy, toInference, draw, age;
        final long uiDelay, lastStall, updateCost;
        final int queue;
        final boolean busy;
        final Sample inference, latency;

        Data(double camera, double model, double overlay, double preview, long received, long dropped,
                long processed, long forwarded, long deliveryDrops, long stale, long rejectedPoses, long lastFrame,
                long lastCallback, long preprocess, long postprocess, long filter, long angles, long copy,
                long toInference, long draw, long age, long uiDelay, long lastStall, long updateCost,
                int queue, boolean busy, Sample inference, Sample latency) {
            this.camera = camera;
            this.model = model;
            this.overlay = overlay;
            this.preview = preview;
            this.received = received;
            this.dropped = dropped;
            this.processed = processed;
            this.forwarded = forwarded;
            this.deliveryDrops = deliveryDrops;
            this.stale = stale;
            this.rejectedPoses = rejectedPoses;
            this.lastFrame = lastFrame;
            this.lastCallback = lastCallback;
            this.preprocess = preprocess;
            this.postprocess = postprocess;
            this.filter = filter;
            this.angles = angles;
            this.copy = copy;
            this.toInference = toInference;
            this.draw = draw;
            this.age = age;
            this.uiDelay = uiDelay;
            this.lastStall = lastStall;
            this.updateCost = updateCost;
            this.queue = queue;
            this.busy = busy;
            this.inference = inference;
            this.latency = latency;
        }
    }

    static final class Sample {
        final long min, max, mean, p50, p95;
        final int count;

        Sample(long[] ring, int total) {
            count = Math.min(ring.length, total);
            long[] values = Arrays.copyOf(ring, count);
            Arrays.sort(values);
            long sum = 0;
            for (long n : values) sum += n;
            min = count == 0 ? -1 : values[0];
            max = count == 0 ? -1 : values[count - 1];
            mean = count == 0 ? -1 : sum / count;
            p50 = count == 0 ? -1 : values[(count - 1) / 2];
            p95 = count == 0 ? -1 : values[(int) Math.ceil(count * .95) - 1];
        }
    }

    private static final class Rate {
        private final long[] times = new long[120];
        private int next, count;

        void add(long time) {
            times[next] = time;
            next = (next + 1) % times.length;
            if (count < times.length) count++;
        }

        void clear() { next = count = 0; }

        double fps(long now) {
            int observed = 0;
            long first = 0;
            for (int i = 1; i <= count; i++) {
                long time = times[(next + times.length - i) % times.length];
                if (time < now - 2000) break;
                first = time;
                observed++;
            }
            if (observed == 0) return 0;
            if (observed == 1) return -1;
            return 1000.0 * (observed - 1) / Math.max(1, times[(next + times.length - 1) % times.length] - first);
        }
    }
}
