package com.example.fitcounter;

final class PoseAnalyzer {
    private final PoseEstimator estimator;
    private final PoseValidator validator = new PoseValidator();
    private final PoseSmoother smoother = new PoseSmoother();
    private final PoseCrop crop = new PoseCrop();
    private FrameTransform full, transform;
    private long lastTime, lastConfirmed = -1, lastRetry = -1;

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
            lastConfirmed = lastRetry = -1;
        }
        if (frame.time - lastTime > 450) transform = full;
        lastTime = frame.time;
        FrameTransform current = transform;
        Pose candidate = estimator.estimate(frame, current);
        long preprocess = estimator.preprocessingNanos();
        long inference = estimator.inferenceNanos();
        float[] scores = candidate == null ? null : candidate.confidence.clone();
        PoseValidator.Evidence evidence = validator.inspect(candidate, current);
        boolean retried = false;
        if (evidence == null && current.cropSize != 0 && lastConfirmed >= 0
                && frame.time - lastConfirmed <= 350 && (lastRetry < 0 || frame.time - lastRetry >= 320)) {
            // Область сопровождения могла отстать от движения. Проверяем весь этот же кадр,
            // чтобы счётчик не терял нижнюю точку до обработки следующего кадра.
            retried = true;
            lastRetry = frame.time;
            current = full;
            candidate = estimator.estimate(frame, current);
            preprocess += estimator.preprocessingNanos();
            inference += estimator.inferenceNanos();
            scores = candidate == null ? null : candidate.confidence.clone();
            evidence = validator.inspect(candidate, current);
        }
        Pose raw = validator.accept(evidence, frame.time) ? candidate : null;
        if (raw != null) lastConfirmed = frame.time;
        transform = raw == null ? full : crop.next(full, current, raw);
        long validated = System.nanoTime();
        Pose display = smoother.apply(raw, frame.time, Math.min(frame.width, frame.height));
        long filtered = System.nanoTime();
        return new PoseReading(raw, display, candidate, scores, validator.reason, current, frame,
                started - frame.arrivalNanos, preprocess, inference,
                Math.max(0, validated - started - preprocess - inference), filtered - validated, retried);
    }
}
