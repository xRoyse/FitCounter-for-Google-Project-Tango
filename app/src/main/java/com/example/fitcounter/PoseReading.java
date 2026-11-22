package com.example.fitcounter;

final class PoseReading {
    final Pose pose;
    final FrameTransform transform;
    final long time, frameId, arrivalNanos;
    final long queueNanos, preprocessNanos, inferenceNanos, postprocessNanos, filterNanos, angleNanos;
    final Limb[][] limbs = new Limb[Exercise.values().length][2];

    PoseReading(Pose raw, Pose display, FrameTransform transform, Frame frame, long queueNanos,
                long preprocessNanos, long inferenceNanos, long postprocessNanos, long filterNanos) {
        pose = display;
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
        if (raw != null) {
            for (Exercise exercise : Exercise.values()) {
                for (int side = 0; side < 2; side++) {
                    limbs[exercise.ordinal()][side] = measure(raw, exercise, side);
                }
            }
        }
        angleNanos = System.nanoTime() - start;
    }

    private static Limb measure(Pose pose, Exercise exercise, int side) {
        int a = exercise.joint(0, side);
        int b = exercise.joint(1, side);
        int c = exercise.joint(2, side);
        float confidence = Math.min(pose.confidence[a],
                Math.min(pose.confidence[b], pose.confidence[c]));
        if (confidence < PoseValidator.COUNT_CONFIDENCE) return null;
        double first = pose.distance(a, b), second = pose.distance(b, c);
        if (first < 2 || second < 2 || first > 4.5 * second || second > 4.5 * first) return null;
        double angle = JointAngle.degrees(pose.x[a], pose.y[a], pose.x[b], pose.y[b],
                pose.x[c], pose.y[c]);
        if (Double.isNaN(angle)) return null;
        if (exercise == Exercise.PUSH_UP) {
            int hip = pose.visible(11 + side, PoseValidator.DRAW_CONFIDENCE) ? 11 + side : 12 - side;
            if (!pose.visible(hip, PoseValidator.DRAW_CONFIDENCE)) return null;
            double dx = Math.abs(pose.x[a] - pose.x[hip]);
            double dy = Math.abs(pose.y[a] - pose.y[hip]);
            if (dx < 0.85 * dy || dx < 0.40 * (first + second)) return null;
            return new Limb(confidence, angle, pose.y[c] > pose.y[a],
                    pose.y[c] - pose.y[a], first + second);
        }
        double height = pose.y[c] - pose.y[a];
        if (height < 0.06 * (first + second)) return null;
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
