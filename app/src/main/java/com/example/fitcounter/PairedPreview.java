package com.example.fitcounter;

import android.graphics.Bitmap;

/** Копирует кадр для одновременной отрисовки изображения и его скелета. */
final class PairedPreview {
    private int[] colors;
    private int[] luma, chroma;
    private FrameTransform mapped;
    private int width, height;

    Bitmap copy(Frame frame, FrameTransform transform) {
        if (mapped == null || mapped.width != transform.width || mapped.height != transform.height
                || mapped.rotation != transform.rotation) prepare(transform);
        byte[] data = frame.data;
        for (int i = 0; i < colors.length; i++) {
            int uv = chroma[i];
            int v = (data[uv] & 255) - 128, u = (data[uv + 1] & 255) - 128;
            int y = Math.max(0, (data[luma[i]] & 255) - 16) * 298;
            int r = clip((y + 409 * v + 128) >> 8);
            int g = clip((y - 100 * u - 208 * v + 128) >> 8);
            int b = clip((y + 516 * u + 128) >> 8);
            colors[i] = 0xff000000 | r << 16 | g << 8 | b;
        }
        // Рендерер получает неизменяемую копию. Camera может сразу вернуть NV21 в оборот.
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.RGB_565);
    }

    private void prepare(FrameTransform transform) {
        mapped = transform;
        int stride = Math.max(transform.uprightWidth, transform.uprightHeight) > 400 ? 2 : 1;
        width = transform.uprightWidth / stride;
        height = transform.uprightHeight / stride;
        int bytes = transform.width * transform.height;
        colors = new int[width * height];
        luma = new int[colors.length];
        chroma = new int[colors.length];
        for (int y = 0, i = 0; y < height; y++) {
            for (int x = 0; x < width; x++, i++) {
                int p = transform.sensorIndex(x * stride, y * stride);
                luma[i] = p;
                chroma[i] = bytes + FramePreprocessor.chromaOffset(p, transform.width);
            }
        }
    }

    private static int clip(int value) {
        return Math.min(255, Math.max(0, value));
    }
}
