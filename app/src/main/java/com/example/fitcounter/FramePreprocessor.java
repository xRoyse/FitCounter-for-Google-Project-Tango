package com.example.fitcounter;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

final class FramePreprocessor {
    private final int[] luma, chroma;
    private final int[] columnLuma, columnChroma;
    private final byte[] rgb;
    private final float[] normalized;
    private final FloatBuffer floatInput;
    private final ByteBuffer input;
    private FrameTransform mapped;

    FramePreprocessor(int size, ByteBuffer input, boolean normalize) {
        int pixels = size * size;
        this.input = input;
        luma = new int[pixels];
        chroma = new int[pixels];
        columnLuma = new int[size];
        columnChroma = new int[size];
        rgb = new byte[pixels * 3];
        normalized = normalize ? new float[rgb.length] : null;
        floatInput = normalize ? input.asFloatBuffer() : null;
    }

    void fill(Frame frame, FrameTransform transform) {
        if (mapped != transform) map(transform);
        byte[] data = frame.data;
        for (int i = 0, out = 0; i < luma.length; i++, out += 3) {
            int p = luma[i];
            if (p < 0) continue;
            int uv = chroma[i];
            int v = (data[uv] & 255) - 128, u = (data[uv + 1] & 255) - 128;
            int y = Math.max(0, (data[p] & 255) - 16) * 298;
            rgb[out] = clip((y + 409 * v + 128) >> 8);
            rgb[out + 1] = clip((y - 100 * u - 208 * v + 128) >> 8);
            rgb[out + 2] = clip((y + 516 * u + 128) >> 8);
        }
        input.clear();
        if (normalized == null) {
            input.put(rgb);
            input.rewind();
        } else {
            for (int i = 0; i < rgb.length; i++) normalized[i] = ((rgb[i] & 255) - 128) / 128f;
            floatInput.clear();
            floatInput.put(normalized);
        }
    }

    private void map(FrameTransform transform) {
        mapped = transform;
        int size = transform.inputSize, width = transform.width, ySize = width * transform.height;
        java.util.Arrays.fill(rgb, (byte) 0);
        int origin = transform.sensorIndex(0, 0);
        for (int col = 0; col < size; col++) {
            float x = (col + 0.5f - transform.left) / transform.scale;
            if (x < 0 || x >= transform.uprightWidth) {
                columnLuma[col] = Integer.MIN_VALUE;
            } else {
                int p = transform.sensorIndex((int) x, 0);
                columnLuma[col] = p - origin;
                columnChroma[col] = chromaOffset(p, width) - chromaOffset(origin, width);
            }
        }
        for (int row = 0, i = 0; row < size; row++) {
            float y = (row + 0.5f - transform.top) / transform.scale;
            if (y < 0 || y >= transform.uprightHeight) {
                java.util.Arrays.fill(luma, i, i + size, -1);
                i += size;
                continue;
            }
            // При каждом из четырёх поворотов координаты сенсора раскладываются по двум осям.
            int base = transform.sensorIndex(0, (int) y);
            int uv = ySize + chromaOffset(base, width);
            for (int col = 0; col < size; col++, i++) {
                luma[i] = columnLuma[col] == Integer.MIN_VALUE ? -1 : base + columnLuma[col];
                chroma[i] = uv + columnChroma[col];
            }
        }
    }

    static int chromaOffset(int sensorIndex, int width) {
        return (sensorIndex / width / 2) * width + (sensorIndex % width & ~1);
    }

    private static byte clip(int value) {
        return (byte) Math.min(255, Math.max(0, value));
    }
}
