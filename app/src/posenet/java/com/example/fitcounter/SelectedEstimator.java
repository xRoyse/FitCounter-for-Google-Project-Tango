package com.example.fitcounter;

import android.content.Context;
import java.io.IOException;

final class SelectedEstimator {
    private SelectedEstimator() {}

    static PoseEstimator open(Context context) throws IOException {
        return new PoseNetEstimator(PoseEstimator.model(context, "posenet.tflite"));
    }
}
