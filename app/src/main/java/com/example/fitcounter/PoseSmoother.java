package com.example.fitcounter;

final class PoseSmoother {
    private Pose previous;
    private long time;

    Pose apply(Pose raw, long timestamp, int scale) {
        if (raw == null) {
            previous = null;
            return null;
        }
        Pose result = new Pose();
        long elapsed = timestamp - time;
        for (int i = 0; i < Pose.SIZE; i++) {
            result.confidence[i] = raw.confidence[i];
            result.x[i] = raw.x[i];
            result.y[i] = raw.y[i];
            if (previous != null && elapsed > 0 && elapsed <= 250
                    && raw.visible(i, PoseValidator.DRAW_CONFIDENCE)
                    && previous.visible(i, PoseValidator.DRAW_CONFIDENCE)) {
                float dx = raw.x[i] - previous.x[i], dy = raw.y[i] - previous.y[i];
                // У неподвижной точки подавляем дрожание, быстрое движение рисуем сразу.
                double speed = Math.hypot(dx, dy) * 1000 / (scale * (double) elapsed);
                double tau = 55 / (1 + 12 * speed);
                float alpha = (float) (elapsed / (elapsed + tau));
                result.x[i] = previous.x[i] + alpha * dx;
                result.y[i] = previous.y[i] + alpha * dy;
            }
        }
        previous = result;
        time = timestamp;
        return result;
    }
}
