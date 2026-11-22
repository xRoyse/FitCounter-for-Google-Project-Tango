package com.example.fitcounter;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test public void storesResultsOnceAndReportsCalendarDaysAndTotals() {
        Context context = instrumentation().getTargetContext();
        String file = "fitcounter-test-" + UUID.randomUUID() + ".db";
        TimeZone zone = TimeZone.getTimeZone("Europe/Volgograd");
        Calendar date = Calendar.getInstance(zone);
        date.clear();
        date.set(2024, Calendar.JUNE, 13, 0, 0);
        long start = date.getTimeInMillis();
        long end = DateRanges.next(date, Calendar.DAY_OF_MONTH, 1);
        WorkoutRepository repository = new WorkoutRepository(context, file);
        try {
            WorkoutRecord result = new WorkoutRecord("repeat-id", start, Exercise.PUSH_UP, 8);
            repository.save(result);
            repository.save(result);
            repository.save(new WorkoutRecord("squat-id", end - 1, Exercise.SQUAT, 5));
            repository.save(new WorkoutRecord("tomorrow", end, Exercise.PUSH_UP, 3));
            repository.close();
            repository = new WorkoutRepository(context, file);
            assertEquals(2, repository.history(start, end).size());
            assertEquals(Exercise.SQUAT, repository.history(start, end).get(0).exercise);
            assertTrue(repository.trainingDays(start, end, zone).contains(13));
            assertFalse(repository.trainingDays(start, end, zone).contains(14));
            assertArrayEquals(new long[]{8, 5}, repository.totals(start, end));
            assertArrayEquals(new long[]{11, 5}, repository.totals(start, end + 1));
        } finally {
            repository.close();
            context.deleteDatabase(file);
        }
    }

    @Test public void packagedModelFindsKeypointsOnAPhoto() throws Exception {
        Instrumentation test = instrumentation();
        Bitmap image;
        try (InputStream stream = test.getContext().getAssets().open("push-up.jpg")) {
            image = BitmapFactory.decodeStream(stream);
        }
        assertNotNull(image);
        int width = image.getWidth() & ~1, height = image.getHeight() & ~1;
        Frame frame = new Frame(nv21(image, width, height), width, height, 0, 0, () -> {});
        image.recycle();
        try (PoseEstimator estimator = PoseEstimator.open(test.getTargetContext())) {
            FrameTransform transform = new FrameTransform(width, height, 0, estimator.size());
            Pose pose = estimator.estimate(frame, transform);
            int visible = 0;
            for (int joint = 5; joint < Pose.SIZE; joint++) {
                if (pose.visible(joint, 0.30f) && pose.x[joint] >= 0 && pose.x[joint] < width
                        && pose.y[joint] >= 0 && pose.y[joint] < height) visible++;
            }
            assertTrue("Expected visible body joints, got " + visible, visible >= 4);

            long[] samples = new long[30];
            for (int sample = 0; sample < samples.length; sample++) {
                estimator.estimate(frame, transform);
                samples[sample] = estimator.inferenceNanos() / 1_000_000;
            }
            Arrays.sort(samples);
            Log.i("FitCounter", "Photo inference on " + estimator.getClass().getSimpleName()
                    + ", ms p50/p95=" + samples[14] + "/" + samples[28]);
        }
    }

    @SuppressWarnings("deprecation")
    @Test public void cameraReceivesFramesAfterReopening() throws Exception {
        assumeTrue("The test device has no camera", Camera.getNumberOfCameras() > 0);
        CameraFrameSource source = new CameraFrameSource();
        SurfaceTexture texture = new SurfaceTexture(0);
        try {
            verifyFrames(source, texture, FrameSource.Facing.AUTO);
            source.stop();
            verifyFrames(source, texture, FrameSource.Facing.AUTO);
        } finally {
            releaseCamera(source, texture);
        }
    }

    @Test public void opensCalendarAndStatisticsFromTrainingScreen() {
        Instrumentation test = instrumentation();
        Intent intent = new Intent(test.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) test.startActivitySync(intent);
        try {
            test.runOnMainSync(() -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.training_screen).getVisibility());
                activity.findViewById(R.id.tab_calendar).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.calendar_screen).getVisibility());
                TextView month = activity.findViewById(R.id.month_label);
                assertTrue(month.getText().length() > 0);
                activity.findViewById(R.id.tab_stats).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.stats_screen).getVisibility());
            });
        } finally {
            test.runOnMainSync(activity::finish);
        }
    }

    @SuppressWarnings("deprecation")
    @Test public void cameraFeedsThePackagedModel() throws Exception {
        assumeTrue("The test device has no camera", Camera.getNumberOfCameras() > 0);
        Context context = instrumentation().getTargetContext();
        CountDownLatch frames = new CountDownLatch(60);
        AtomicBoolean failure = new AtomicBoolean();
        Runnable reportError = () -> {
            failure.set(true);
            while (frames.getCount() > 0) frames.countDown();
        };
        CameraFrameSource source = new CameraFrameSource();
        SurfaceTexture texture = new SurfaceTexture(0);
        PosePipeline pipeline = new PosePipeline(context, new PosePipeline.Listener() {
            @Override public void onReady() {}
            @Override public void onPose(PoseReading reading) { frames.countDown(); }
            @Override public void onModelError() { reportError.run(); }
        });
        try {
            source.start(texture, 0, FrameSource.Facing.AUTO, new FrameSource.Listener() {
                @Override public void onPreview(int width, int height, boolean mirror) {}
                @Override public void onFrame(Frame frame) { pipeline.offer(frame); }
                @Override public void onCameraError() { reportError.run(); }
            });
            assertTrue("Inference stream timed out", frames.await(45, TimeUnit.SECONDS));
            assertFalse("Camera or pose model failed", failure.get());
        } finally {
            pipeline.close();
            releaseCamera(source, texture);
        }
    }

    private static void releaseCamera(FrameSource source, SurfaceTexture texture) throws InterruptedException {
        CountDownLatch released = new CountDownLatch(1);
        source.stop(() -> {
            texture.release();
            released.countDown();
        });
        assertTrue("Camera release timed out", released.await(10, TimeUnit.SECONDS));
    }

    private static boolean verifyFrames(FrameSource source, SurfaceTexture texture, FrameSource.Facing facing)
            throws InterruptedException {
        CountDownLatch callback = new CountDownLatch(1);
        AtomicBoolean gotFrame = new AtomicBoolean();
        AtomicBoolean mirrored = new AtomicBoolean();
        source.start(texture, 0, facing, new FrameSource.Listener() {
            @Override public void onPreview(int width, int height, boolean mirror) { mirrored.set(mirror); }
            @Override public void onFrame(Frame frame) {
                gotFrame.set(frame.width > 0 && frame.height > 0 && frame.data.length > 0);
                frame.release();
                callback.countDown();
            }
            @Override public void onCameraError() { callback.countDown(); }
        });
        assertTrue("Camera callback timed out", callback.await(10, TimeUnit.SECONDS));
        assertTrue("Camera did not provide an NV21 frame", gotFrame.get());
        return mirrored.get();
    }

    private static byte[] nv21(Bitmap image, int width, int height) {
        int size = width * height;
        int[] rgb = new int[size];
        image.getPixels(rgb, 0, width, 0, 0, width, height);
        byte[] data = new byte[size * 3 / 2];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                int r = rgb[index] >> 16 & 255, g = rgb[index] >> 8 & 255, b = rgb[index] & 255;
                data[index] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                if ((row & 1) == 0 && (col & 1) == 0) {
                    int uv = size + (row / 2) * width + col;
                    data[uv] = (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                    data[uv + 1] = (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                }
            }
        }
        return data;
    }

    private static Instrumentation instrumentation() {
        return InstrumentationRegistry.getInstrumentation();
    }
}
