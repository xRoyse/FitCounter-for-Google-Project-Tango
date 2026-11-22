package com.example.fitcounter;

final class PoseReading {
    final Pose pose;
    final FrameTransform transform;
    final long time;
    final Limb[][] limbs = new Limb[Exercise.values().length][2];

    PoseReading(Pose pose, FrameTransform transform, long time) {
        this.pose = pose;
        this.transform = transform;
        this.time = time;
        for (Exercise exercise : Exercise.values()) {
            for (int side = 0; side < 2; side++) {
                limbs[exercise.ordinal()][side] = measure(exercise, side);
            }
        }
    }

    private Limb measure(Exercise exercise, int side) {
        int a = exercise.joint(0, side);
        int b = exercise.joint(1, side);
        int c = exercise.joint(2, side);
        float confidence = Math.min(pose.confidence[a],
                Math.min(pose.confidence[b], pose.confidence[c]));
        if (confidence < PoseFilter.COUNT_CONFIDENCE) return null;
        double angle = JointAngle.degrees(pose.x[a], pose.y[a], pose.x[b], pose.y[b],
                pose.x[c], pose.y[c]);
        if (Double.isNaN(angle)) return null;
        if (exercise == Exercise.PUSH_UP) {
            int hip = 11 + side;
            if (!pose.visible(hip, PoseFilter.DRAW_CONFIDENCE)) return null;
            double dx = Math.abs(pose.x[a] - pose.x[hip]);
            double dy = Math.abs(pose.y[a] - pose.y[hip]);
            if (dx < dy || dx < transform.uprightWidth * 0.07) return null;
            return new Limb(confidence, angle, true, 0, 0);
        }
        double legLength = Math.hypot(pose.x[a] - pose.x[b], pose.y[a] - pose.y[b])
                + Math.hypot(pose.x[b] - pose.x[c], pose.y[b] - pose.y[c]);
        if (legLength < transform.uprightHeight * 0.12) return null;
        return new Limb(confidence, angle, pose.y[a] < pose.y[b] && pose.y[b] < pose.y[c],
                pose.y[c] - pose.y[a], legLength);
    }

    static final class Limb {
        final float confidence;
        final double angle, hipHeight, legLength;
        final boolean startPosition;

        Limb(float confidence, double angle, boolean startPosition, double hipHeight, double legLength) {
            this.confidence = confidence;
            this.angle = angle;
            this.startPosition = startPosition;
            this.hipHeight = hipHeight;
            this.legLength = legLength;
        }
    }
}
