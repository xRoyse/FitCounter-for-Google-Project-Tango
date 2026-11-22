package com.example.fitcounter;

final class Frame {
    final byte[] data;
    final int width, height, rotation;
    final long time;
    private final Runnable returnBuffer;

    Frame(byte[] data, int width, int height, int rotation, long time, Runnable returnBuffer) {
        this.data = data;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.time = time;
        this.returnBuffer = returnBuffer;
    }

    void release() {
        returnBuffer.run();
    }
}
