package com.example.fitcounter;

import java.nio.ByteBuffer;

final class FramePreprocessor {
    private FramePreprocessor() {}

    static void fill(Frame frame, FrameTransform transform, ByteBuffer input, boolean floatInput) {
        input.clear();
        int size = transform.inputSize;
        int ySize = frame.width * frame.height;
        for (int row = 0; row < size; row++) {
            float y = (row + 0.5f - transform.top) / transform.scale;
            for (int col = 0; col < size; col++) {
                float x = (col + 0.5f - transform.left) / transform.scale;
                if (x < 0 || y < 0 || x >= transform.uprightWidth || y >= transform.uprightHeight) {
                    putRgb(input, floatInput, 0, 0, 0);
                    continue;
                }
                int p = transform.sensorIndex((int) x, (int) y);
                int uv = ySize + (p / frame.width / 2) * frame.width + (p % frame.width & ~1);
                int v = (frame.data[uv] & 255) - 128;
                int u = (frame.data[uv + 1] & 255) - 128;
                int luma = Math.max(0, (frame.data[p] & 255) - 16) * 298;
                putRgb(input, floatInput, clip((luma + 409 * v + 128) >> 8),
                        clip((luma - 100 * u - 208 * v + 128) >> 8),
                        clip((luma + 516 * u + 128) >> 8));
            }
        }
        input.rewind();
    }

    private static void putRgb(ByteBuffer input, boolean floatInput, int r, int g, int b) {
        if (floatInput) {
            input.putFloat((r - 128) / 128f);
            input.putFloat((g - 128) / 128f);
            input.putFloat((b - 128) / 128f);
        } else {
            input.put((byte) r);
            input.put((byte) g);
            input.put((byte) b);
        }
    }

    private static int clip(int value) {
        return Math.min(255, Math.max(0, value));
    }
}
