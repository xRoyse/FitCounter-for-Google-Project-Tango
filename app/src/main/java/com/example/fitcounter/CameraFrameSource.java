package com.example.fitcounter;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.util.List;

@SuppressWarnings("deprecation")
final class CameraFrameSource implements FrameSource {
    private static final long FIRST_FRAME_TIMEOUT_MS = 4000;
    private static final Handler WORKER = startWorker();
    private Camera camera;
    private Runnable firstFrameTimeout;

    private static Handler startWorker() {
        HandlerThread thread = new HandlerThread("fitcounter-camera");
        thread.start();
        return new Handler(thread.getLooper());
    }

    @Override public void start(SurfaceTexture surface, int displayDegrees, Facing facing, Listener listener) {
        WORKER.post(() -> open(surface, displayDegrees, facing, listener));
    }

    private void open(SurfaceTexture surface, int displayDegrees, Facing facing, Listener listener) {
        release();
        try {
            int count = Camera.getNumberOfCameras();
            Camera.CameraInfo[] cameras = new Camera.CameraInfo[count];
            boolean hasFront = false;
            boolean hasRear = false;
            for (int id = 0; id < count; id++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(id, info);
                boolean front = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
                // На Yellowstone задний цветной поток доступен через Camera ID 0.
                if ("yellowstone".equals(Build.DEVICE) && !front && id != 0) continue;
                cameras[id] = info;
                hasFront |= front;
                hasRear |= !front;
            }
            listener.onCameraOptions(hasFront && hasRear);
            for (int pass = 0; pass < 2; pass++) {
                boolean front = pass == 1;
                if (facing != Facing.AUTO && front != (facing == Facing.FRONT)) continue;
                for (int id = 0; id < count; id++) {
                    Camera.CameraInfo info = cameras[id];
                    if (info == null || front != (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)) continue;
                    try {
                        startCamera(id, Camera.open(id), surface, displayDegrees, info.orientation,
                                front, listener);
                        if (BuildConfig.DEBUG) Log.d("FitCounter", "Camera ID " + id + " streaming NV21");
                        return;
                    } catch (IOException | RuntimeException e) {
                        Log.w("FitCounter", "Camera preview failed for id " + id, e);
                        release();
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w("FitCounter", "Camera enumeration failed", e);
            release();
        }
        listener.onCameraError("CAMERA_OPEN_FAILED");
    }

    private void startCamera(int id, Camera opened, SurfaceTexture surface, int displayDegrees,
            int sensorDegrees, boolean front, Listener listener) throws IOException {
        camera = opened;
        Camera.Parameters parameters = opened.getParameters();
        if (!parameters.getSupportedPreviewFormats().contains(ImageFormat.NV21)) {
            throw new IOException("NV21 unavailable");
        }
        Camera.Size requested = chooseSize(parameters.getSupportedPreviewSizes());
        parameters.setPreviewFormat(ImageFormat.NV21);
        parameters.setPreviewSize(requested.width, requested.height);
        List<Integer> rates = parameters.getSupportedPreviewFrameRates();
        if (rates != null) {
            int chosenRate = 0;
            for (int rate : rates) {
                if (rate > 0 && (chosenRate == 0 || Math.abs(rate - 30) < Math.abs(chosenRate - 30))) {
                    chosenRate = rate;
                }
            }
            if (chosenRate > 0) parameters.setPreviewFrameRate(chosenRate);
        }
        List<String> focus = parameters.getSupportedFocusModes();
        if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }
        opened.setParameters(parameters);
        Camera.Size actual = opened.getParameters().getPreviewSize();
        int width = actual.width, height = actual.height;
        if ((width & 1) != 0 || (height & 1) != 0) throw new IOException("Unsupported NV21 dimensions");
        int sensorToUpright = (sensorDegrees + (front ? displayDegrees : -displayDegrees) + 360) % 360;
        opened.setDisplayOrientation(front ? (360 - sensorToUpright) % 360 : sensorToUpright);
        opened.setPreviewTexture(surface);
        int bytes = width * height * 3 / 2;
        for (int i = 0; i < 3; i++) opened.addCallbackBuffer(new byte[bytes]);
        opened.setPreviewCallbackWithBuffer((data, owner) -> {
            if (data == null || owner != camera) return;
            cancelFirstFrameTimeout();
            if (data.length < bytes) {
                release();
                listener.onCameraError("INVALID_NV21_BUFFER");
                return;
            }
            listener.onFrame(new Frame(data, width, height, sensorToUpright,
                    SystemClock.uptimeMillis(), () -> WORKER.post(() -> {
                        if (camera != owner) return;
                        try {
                            owner.addCallbackBuffer(data);
                        } catch (RuntimeException e) {
                            Log.w("FitCounter", "Camera rejected preview buffer", e);
                            release();
                            listener.onCameraError("CALLBACK_BUFFER_REJECTED");
                        }
                    })));
        });
        opened.setErrorCallback((error, owner) -> {
            if (owner == camera) {
                release();
                listener.onCameraError("CAMERA_HAL_ERROR_" + error);
            }
        });
        opened.startPreview();
        firstFrameTimeout = () -> {
            if (camera == opened) {
                release();
                listener.onCameraError("FIRST_FRAME_TIMEOUT");
            }
        };
        WORKER.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS);
        boolean swapped = sensorToUpright % 180 != 0;
        listener.onCameraConfigured(id, width, height, sensorDegrees, sensorToUpright, displayDegrees, front);
        listener.onPreview(swapped ? height : width, swapped ? width : height, front);
    }

    private static Camera.Size chooseSize(List<Camera.Size> sizes) {
        Camera.Size best = null;
        long distance = Long.MAX_VALUE;
        for (Camera.Size size : sizes) {
            if ((size.width & 1) != 0 || (size.height & 1) != 0) continue;
            int wide = Math.max(size.width, size.height), narrow = Math.min(size.width, size.height);
            long delta = Math.abs(wide - 640L) + Math.abs(narrow - 480L);
            if (delta < distance) {
                best = size;
                distance = delta;
            }
        }
        if (best == null) throw new IllegalStateException("No compatible preview size");
        return best;
    }

    @Override public void stop(Runnable afterRelease) {
        WORKER.post(() -> {
            release();
            if (afterRelease != null) afterRelease.run();
        });
    }

    private void release() {
        cancelFirstFrameTimeout();
        Camera old = camera;
        camera = null;
        if (old == null) return;
        try {
            old.setPreviewCallbackWithBuffer(null);
            old.stopPreview();
            old.setPreviewTexture(null);
        } catch (IOException | RuntimeException e) {
            Log.w("FitCounter", "Camera preview already stopped", e);
        }
        try {
            old.release();
        } catch (RuntimeException e) {
            Log.w("FitCounter", "Camera already released", e);
        }
    }

    private void cancelFirstFrameTimeout() {
        if (firstFrameTimeout != null) {
            WORKER.removeCallbacks(firstFrameTimeout);
            firstFrameTimeout = null;
        }
    }
}
