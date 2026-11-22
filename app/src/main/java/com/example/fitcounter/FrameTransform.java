package com.example.fitcounter;

final class FrameTransform {
    final int width, height;
    final int uprightWidth, uprightHeight;
    final int rotation, inputSize;
    final float scale, left, top;

    FrameTransform(int width, int height, int rotation, int inputSize) {
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.inputSize = inputSize;
        boolean swapped = rotation == 90 || rotation == 270;
        uprightWidth = swapped ? height : width;
        uprightHeight = swapped ? width : height;
        scale = (float) inputSize / Math.max(uprightWidth, uprightHeight);
        left = (inputSize - uprightWidth * scale) / 2f;
        top = (inputSize - uprightHeight * scale) / 2f;
    }

    float uprightX(float normalizedX) {
        return (normalizedX * inputSize - left) / scale;
    }

    float uprightY(float normalizedY) {
        return (normalizedY * inputSize - top) / scale;
    }

    int sensorIndex(int x, int y) {
        switch (rotation) {
            case 90: return (height - 1 - x) * width + y;
            case 180: return (height - 1 - y) * width + width - 1 - x;
            case 270: return x * width + width - 1 - y;
            default: return y * width + x;
        }
    }
}
