package com.example.fitcounter;

final class Pose {
    static final int SIZE = 17;
    final float[] x = new float[SIZE];
    final float[] y = new float[SIZE];
    final float[] confidence = new float[SIZE];

    boolean visible(int joint, float threshold) {
        return confidence[joint] >= threshold;
    }

    double distance(int a, int b) {
        return Math.hypot(x[a] - x[b], y[a] - y[b]);
    }
}
