package com.example.fitcounter;

import java.util.concurrent.atomic.AtomicLong;

final class Frame {
    private static final AtomicLong IDS = new AtomicLong();
    final long id = IDS.incrementAndGet();
    final byte[] data;
    final int width, height, rotation;
    final long time;
    final long arrivalNanos = System.nanoTime();
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
