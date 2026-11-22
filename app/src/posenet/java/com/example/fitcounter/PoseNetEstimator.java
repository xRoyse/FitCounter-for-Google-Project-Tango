package com.example.fitcounter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.tensorflow.lite.Interpreter;

final class PoseNetEstimator implements PoseEstimator {
    private static final int SIZE = 257;
    private static final int GRID = 9;
    private final ByteBuffer input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder());
    private final float[][][][] heat = new float[1][GRID][GRID][Pose.SIZE];
    private final float[][][][] offsets = new float[1][GRID][GRID][Pose.SIZE * 2];
    private final Object[] inputs = {input};
    private final Map<Integer, Object> outputs = new HashMap<>();
    private final Interpreter interpreter;
    private long inferenceNanos;

    PoseNetEstimator(ByteBuffer model) {
        interpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(2));
        outputs.put(0, heat);
        outputs.put(1, offsets);
    }

    @Override public int size() { return SIZE; }

    @Override public Pose estimate(Frame frame, FrameTransform transform) {
        FramePreprocessor.fill(frame, transform, input, true);
        long start = System.nanoTime();
        interpreter.runForMultipleInputsOutputs(inputs, outputs);
        inferenceNanos = System.nanoTime() - start;
        Pose result = new Pose();
        for (int joint = 0; joint < Pose.SIZE; joint++) {
            int row = 0, col = 0;
            for (int y = 0; y < GRID; y++) {
                for (int x = 0; x < GRID; x++) {
                    if (heat[0][y][x][joint] > heat[0][row][col][joint]) {
                        row = y;
                        col = x;
                    }
                }
            }
            float[] offset = offsets[0][row][col];
            result.y[joint] = transform.uprightY((row * 32 + offset[joint]) / SIZE);
            result.x[joint] = transform.uprightX((col * 32 + offset[joint + Pose.SIZE]) / SIZE);
            result.confidence[joint] = (float) (1 / (1 + Math.exp(-heat[0][row][col][joint])));
        }
        return result;
    }

    @Override public void close() { interpreter.close(); }
    @Override public long inferenceNanos() { return inferenceNanos; }
}
