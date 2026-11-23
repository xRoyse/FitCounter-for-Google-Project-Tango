package com.example.fitcounter;

final class PoseReading {
    static final String[] JOINTS = {"NOSE", "LEFT_EYE", "RIGHT_EYE", "LEFT_EAR", "RIGHT_EAR",
            "LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_ELBOW", "RIGHT_ELBOW", "LEFT_WRIST", "RIGHT_WRIST",
            "LEFT_HIP", "RIGHT_HIP", "LEFT_KNEE", "RIGHT_KNEE", "LEFT_ANKLE", "RIGHT_ANKLE"};
    final Pose pose;
    final Pose candidate;
    final float[] modelConfidence;
    final String validation;
    final boolean fullFrameRetry;
    final FrameTransform transform;
    final long time, frameId, arrivalNanos;
    final long queueNanos, preprocessNanos, inferenceNanos, postprocessNanos, filterNanos, angleNanos;
    final Limb[][] limbs = new Limb[Exercise.values().length][2];
    final String[][] limbReasons = new String[Exercise.values().length][2];

    PoseReading(Pose raw, Pose display, FrameTransform transform, Frame frame, long queueNanos,
                long preprocessNanos, long inferenceNanos, long postprocessNanos, long filterNanos) {
        this(raw, display, raw, raw == null ? null : raw.confidence, raw == null ? "PERSON_NOT_DETECTED"
                : "CONFIRMED", transform, frame, queueNanos, preprocessNanos, inferenceNanos, postprocessNanos,
                filterNanos, false);
    }

    PoseReading(Pose raw, Pose display, Pose candidate, float[] modelConfidence, String validation,
                FrameTransform transform, Frame frame, long queueNanos, long preprocessNanos,
                long inferenceNanos, long postprocessNanos, long filterNanos, boolean fullFrameRetry) {
        pose = display;
        this.candidate = candidate;
        this.modelConfidence = modelConfidence;
        this.validation = validation;
        this.fullFrameRetry = fullFrameRetry;
        this.transform = transform;
        time = frame.time;
        frameId = frame.id;
        arrivalNanos = frame.arrivalNanos;
        this.queueNanos = queueNanos;
        this.preprocessNanos = preprocessNanos;
        this.inferenceNanos = inferenceNanos;
        this.postprocessNanos = postprocessNanos;
        this.filterNanos = filterNanos;
        long start = System.nanoTime();
        for (Exercise exercise : Exercise.values()) {
            for (int side = 0; side < 2; side++) {
                limbReasons[exercise.ordinal()][side] = validation;
                if (raw != null) limbs[exercise.ordinal()][side] = measure(raw, exercise, side,
                        limbReasons[exercise.ordinal()]);
            }
        }
        angleNanos = System.nanoTime() - start;
    }

    private static Limb measure(Pose pose, Exercise exercise, int side, String[] reasons) {
        int a = exercise.joint(0, side);
        int b = exercise.joint(1, side);
        int c = exercise.joint(2, side);
        float confidence = Math.min(pose.confidence[a],
                Math.min(pose.confidence[b], pose.confidence[c]));
        if (confidence < PoseValidator.COUNT_CONFIDENCE) {
            int joint = confidence == pose.confidence[a] ? a : confidence == pose.confidence[b] ? b : c;
            reasons[side] = JOINTS[joint] + "_NOT_RELIABLE";
            return null;
        }
        reasons[side] = "INVALID_LIMB_GEOMETRY";
        double first = pose.distance(a, b), second = pose.distance(b, c);
        if (first < 2 || second < 2 || first > 4.5 * second || second > 4.5 * first) return null;
        double angle = JointAngle.degrees(pose.x[a], pose.y[a], pose.x[b], pose.y[b],
                pose.x[c], pose.y[c]);
        if (Double.isNaN(angle)) return null;
        if (exercise == Exercise.PUSH_UP) {
            int hip = pose.visible(11 + side, PoseValidator.DRAW_CONFIDENCE) ? 11 + side : 12 - side;
            reasons[side] = "HIP_NOT_RELIABLE";
            if (!pose.visible(hip, PoseValidator.DRAW_CONFIDENCE)) return null;
            double dx = Math.abs(pose.x[a] - pose.x[hip]);
            double dy = Math.abs(pose.y[a] - pose.y[hip]);
            reasons[side] = "BODY_NOT_HORIZONTAL";
            if (dx < 0.85 * dy || dx < 0.40 * (first + second)) return null;
            reasons[side] = "VALID";
            return new Limb(confidence, angle, pose.y[c] > pose.y[a],
                    pose.y[c] - pose.y[a], first + second);
        }
        double height = pose.y[c] - pose.y[a];
        if (height < 0.06 * (first + second)) return null;
        reasons[side] = "VALID";
        return new Limb(confidence, angle, height > 0.65 * (first + second)
                && pose.y[a] < pose.y[b] && pose.y[b] < pose.y[c], height, first + second);
    }

    static final class Limb {
        final float confidence;
        final double angle, height, limbLength;
        final boolean startPosition;

        Limb(float confidence, double angle, boolean startPosition, double height, double limbLength) {
            this.confidence = confidence;
            this.angle = angle;
            this.startPosition = startPosition;
            this.height = height;
            this.limbLength = limbLength;
        }
    }
}
