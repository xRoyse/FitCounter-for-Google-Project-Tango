package com.example.fitcounter;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

interface PoseEstimator extends AutoCloseable {
    int size();
    Pose estimate(Frame frame, FrameTransform transform);
    long preprocessingNanos();
    long inferenceNanos();
    @Override void close();

    static PoseEstimator open(Context context) throws IOException {
        return SelectedEstimator.open(context);
    }

    static MappedByteBuffer model(Context context, String filename) throws IOException {
        try (AssetFileDescriptor asset = context.getAssets().openFd(filename);
                FileInputStream stream = new FileInputStream(asset.getFileDescriptor())) {
            return stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    asset.getStartOffset(), asset.getDeclaredLength());
        }
    }
}
