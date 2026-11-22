package com.example.fitcounter;

final class PoseAnalyzer {
    private final PoseEstimator estimator;
    private final PoseValidator validator = new PoseValidator();
    private final PoseSmoother smoother = new PoseSmoother();
    private final PoseCrop crop = new PoseCrop();
    private FrameTransform full, transform;
    private long lastTime;

    PoseAnalyzer(PoseEstimator estimator) {
        this.estimator = estimator;
    }

    PoseReading analyze(Frame frame) {
        long started = System.nanoTime();
        if (full == null || full.rotation != frame.rotation
                || full.width != frame.width || full.height != frame.height) {
            full = new FrameTransform(frame.width, frame.height, frame.rotation, estimator.size());
            transform = full;
            validator.reset();
        }
        if (frame.time - lastTime > 450) transform = full;
        lastTime = frame.time;
        FrameTransform current = transform;
        Pose candidate = estimator.estimate(frame, current);
        long estimated = System.nanoTime();
        Pose raw = validator.accept(candidate, current, frame.time) ? candidate : null;
        transform = raw == null ? full : crop.next(full, current, raw);
        long validated = System.nanoTime();
        Pose display = smoother.apply(raw, frame.time, Math.min(frame.width, frame.height));
        long filtered = System.nanoTime();
        long preprocess = estimator.preprocessingNanos();
        long inference = estimator.inferenceNanos();
        return new PoseReading(raw, display, current, frame, started - frame.arrivalNanos,
                preprocess, inference, Math.max(0, estimated - started - preprocess - inference)
                + validated - estimated, filtered - validated);
    }
}
