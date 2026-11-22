package com.example.fitcounter;

import java.nio.ByteBuffer;

final class MoveNetEstimator implements PoseEstimator {
    static { System.loadLibrary("fitcounter_pose"); }

    private static final int SIZE = 192;
    private final ByteBuffer model;
    private final ByteBuffer input = ByteBuffer.allocateDirect(SIZE * SIZE * 3);
    private final float[] output = new float[Pose.SIZE * 3];
    private final long handle;
    private long inferenceNanos;

    MoveNetEstimator(ByteBuffer model) {
        this.model = model;
        handle = create(this.model);
        if (handle == 0) throw new IllegalStateException("Cannot initialize MoveNet");
    }

    @Override public int size() { return SIZE; }

    @Override public Pose estimate(Frame frame, FrameTransform transform) {
        FramePreprocessor.fill(frame, transform, input, false);
        long start = System.nanoTime();
        if (!run(handle, input, output)) throw new IllegalStateException("MoveNet inference failed");
        inferenceNanos = System.nanoTime() - start;
        Pose pose = new Pose();
        for (int i = 0; i < Pose.SIZE; i++) {
            pose.y[i] = transform.uprightY(output[i * 3]);
            pose.x[i] = transform.uprightX(output[i * 3 + 1]);
            pose.confidence[i] = output[i * 3 + 2];
        }
        return pose;
    }

    @Override public void close() { destroy(handle); }
    @Override public long inferenceNanos() { return inferenceNanos; }

    private static native long create(ByteBuffer model);
    private static native boolean run(long handle, ByteBuffer input, float[] output);
    private static native void destroy(long handle);
}
