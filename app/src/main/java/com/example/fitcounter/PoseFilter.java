package com.example.fitcounter;

final class PoseFilter {
    private static final float ALPHA = 0.65f;
    static final float DRAW_CONFIDENCE = 0.30f;
    static final float COUNT_CONFIDENCE = 0.40f;
    private Pose previous;
    private long time;

    Pose apply(Pose pose, FrameTransform transform, long timestamp) {
        boolean continuous = previous != null && timestamp - time <= 900;
        for (int i = 0; i < Pose.SIZE; i++) {
            float x = pose.x[i], y = pose.y[i];
            if (!(x >= 0 && x < transform.uprightWidth && y >= 0 && y < transform.uprightHeight)
                    || !(pose.confidence[i] >= DRAW_CONFIDENCE && pose.confidence[i] <= 1)) {
                pose.confidence[i] = 0;
            }
            if (continuous && pose.visible(i, DRAW_CONFIDENCE)
                    && previous.visible(i, DRAW_CONFIDENCE)) {
                pose.x[i] = previous.x[i] + ALPHA * (x - previous.x[i]);
                pose.y[i] = previous.y[i] + ALPHA * (y - previous.y[i]);
            }
        }
        previous = pose;
        time = timestamp;
        return pose;
    }
}
