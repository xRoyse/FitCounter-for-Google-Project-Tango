package com.example.fitcounter;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Кадры видео, заранее декодированные в тот же NV21, который возвращает Camera. */
final class VideoFrameSource implements AutoCloseable {
    private final DataInputStream input;
    private final byte[] buffer;
    private final int width, height, fps;
    private long index;

    VideoFrameSource(InputStream input, int width, int height, int fps) {
        this.input = new DataInputStream(input);
        this.width = width;
        this.height = height;
        this.fps = fps;
        buffer = new byte[width * height * 3 / 2];
    }

    Frame next() throws IOException {
        int first = input.read();
        if (first < 0) return null;
        buffer[0] = (byte) first;
        input.readFully(buffer, 1, buffer.length - 1);
        return new Frame(buffer, width, height, 0, index++ * 1000 / fps, () -> {});
    }

    @Override public void close() throws IOException { input.close(); }
}
