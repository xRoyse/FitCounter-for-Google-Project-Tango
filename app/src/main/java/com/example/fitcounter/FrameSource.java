package com.example.fitcounter;

import android.graphics.SurfaceTexture;

interface FrameSource {
    enum Facing { AUTO, REAR, FRONT }

    interface Listener {
        default void onCameraOptions(boolean canSwitch) {}
        default void onCameraConfigured(int id, int width, int height, int sensor, int upright,
                                        int display, boolean mirror) {}
        void onPreview(int uprightWidth, int uprightHeight, boolean mirror);
        void onFrame(Frame frame);
        void onCameraError();
        default void onCameraError(String code) { onCameraError(); }
    }

    void start(SurfaceTexture surface, int displayDegrees, Facing facing, Listener listener);
    void stop(Runnable afterRelease);

    default void stop() { stop(null); }
}
