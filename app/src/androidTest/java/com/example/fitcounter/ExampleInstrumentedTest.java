package com.example.fitcounter;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.SystemClock;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test public void measuresSoftwareRenderingOfConfirmedPose() throws Exception {
        Instrumentation test = instrumentation();
        byte[] pixels;
        int width, height;
        try (InputStream stream = test.getContext().getAssets().open("push-up.jpg")) {
            Bitmap image = BitmapFactory.decodeStream(stream);
            assertNotNull(image);
            width = image.getWidth() & ~1;
            height = image.getHeight() & ~1;
            pixels = nv21(image, width, height);
            image.recycle();
        }
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        SkeletonView[] view = new SkeletonView[1];
        test.runOnMainSync(() -> {
            view[0] = new SkeletonView(test.getTargetContext(), null);
            view[0].layout(0, 0, width, height);
        });
        long[] copies = new long[30], draws = new long[30], ages = new long[30];
        PairedPreview preview = new PairedPreview();
        try (PoseEstimator estimator = PoseEstimator.open(test.getTargetContext())) {
            PoseAnalyzer analyzer = new PoseAnalyzer(estimator);
            analyzer.analyze(new Frame(pixels, width, height, 0, 0, () -> {}));
            for (int i = 0; i < copies.length; i++) {
                int index = i;
                Frame frame = new Frame(pixels, width, height, 0, (i + 1) * 125L, () -> {});
                PoseReading reading = analyzer.analyze(frame);
                assertNotNull("The reference person must remain confirmed", reading.pose);
                long startCopy = System.nanoTime();
                Bitmap image = preview.copy(frame, reading.transform);
                copies[i] = System.nanoTime() - startCopy;
                test.runOnMainSync(() -> {
                    view[0].show(reading, image, false);
                    long startDraw = System.nanoTime();
                    view[0].draw(canvas);
                    long drawn = System.nanoTime();
                    draws[index] = drawn - startDraw;
                    ages[index] = drawn - reading.arrivalNanos;
                });
            }
        } finally {
            output.recycle();
        }
        Log.i("FitCounter", "Software preview ms p50/p95 copy=" + percentiles(copies)
                + " draw=" + percentiles(draws) + " buffer-to-draw=" + percentiles(ages));
    }

    private static String percentiles(long[] samples) {
        Arrays.sort(samples);
        return samples[(samples.length - 1) / 2] / 1e6 + "/"
                + samples[(int) Math.ceil(samples.length * 0.95) - 1] / 1e6;
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

    @SuppressWarnings("deprecation")
    @Test public void switchesBetweenRearAndFrontCamera() throws Exception {
        assumeTrue("Both camera directions are required", hasTwoCameraDirections());
        CameraFrameSource source = new CameraFrameSource();
        SurfaceTexture texture = new SurfaceTexture(0);
        try {
            assertFalse(verifyFrames(source, texture, FrameSource.Facing.REAR));
            assertTrue(verifyFrames(source, texture, FrameSource.Facing.FRONT));
            assertFalse(verifyFrames(source, texture, FrameSource.Facing.REAR));
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

    @Test public void switchesLivePreviewWithoutFinishingTheWorkout() {
        assumeTrue("Both camera directions are required", hasTwoCameraDirections());
        Instrumentation test = instrumentation();
        MainActivity activity = (MainActivity) test.startActivitySync(
                new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            View start = activity.findViewById(R.id.start_workout);
            View finish = activity.findViewById(R.id.finish_workout);
            View switchCamera = activity.findViewById(R.id.switch_camera);
            TextView facing = activity.findViewById(R.id.camera_facing);
            TextView status = activity.findViewById(R.id.camera_status);
            AtomicBoolean condition = new AtomicBoolean();
            long deadline = SystemClock.uptimeMillis() + 8000;
            do {
                test.runOnMainSync(() -> condition.set(start.isEnabled() && switchCamera.isShown()
                        && switchCamera.isEnabled()));
                if (!condition.get()) SystemClock.sleep(50);
            } while (!condition.get() && SystemClock.uptimeMillis() < deadline);
            assertTrue("Rear preview and camera switch did not become ready", condition.get());
            test.runOnMainSync(() -> {
                assertEquals(activity.getString(R.string.rear_camera), facing.getText().toString());
                assertTrue(start.performClick());
                assertTrue(finish.isEnabled());
                assertTrue(switchCamera.performClick());
            });
            deadline = SystemClock.uptimeMillis() + 8000;
            do {
                test.runOnMainSync(() -> condition.set(switchCamera.isEnabled()
                        && activity.getString(R.string.front_camera).contentEquals(facing.getText())
                        && !Integer.valueOf(R.string.camera_loading).equals(status.getTag())));
                if (!condition.get()) SystemClock.sleep(50);
            } while (!condition.get() && SystemClock.uptimeMillis() < deadline);
            assertTrue("Front preview did not become ready", condition.get());
            test.runOnMainSync(() -> {
                assertTrue(finish.isEnabled());
                assertFalse(start.isEnabled());
                assertFalse(activity.findViewById(R.id.exercise).isEnabled());
                assertFalse(activity.findViewById(R.id.thresholds).isEnabled());
                assertTrue(finish.performClick());
                assertTrue(start.isEnabled());
            });
        } finally {
            test.runOnMainSync(activity::finish);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean hasTwoCameraDirections() {
        boolean front = false, rear = false;
        for (int id = 0; id < Camera.getNumberOfCameras(); id++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(id, info);
            front |= info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
            rear |= info.facing == Camera.CameraInfo.CAMERA_FACING_BACK;
        }
        return front && rear;
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
            @Override public void onPose(PoseReading reading, Bitmap image) { frames.countDown(); }
            @Override public void onModelError() { reportError.run(); }
        });
        try {
            source.start(texture, 0, FrameSource.Facing.AUTO, new FrameSource.Listener() {
                @Override public void onPreview(int width, int height, boolean mirror) {}
                @Override public void onFrame(Frame frame) { pipeline.offer(frame); }
                @Override public void onCameraError() { reportError.run(); }
            });
            assertTrue("Inference stream timed out", frames.await(90, TimeUnit.SECONDS));
            assertFalse("Camera or pose model failed", failure.get());
        } finally {
            pipeline.close();
            releaseCamera(source, texture);
        }
    }

    @Test public void preservesTheLastFrameInABurstAndReleasesEveryBuffer() throws Exception {
        Context context = instrumentation().getTargetContext();
        CountDownLatch ready = new CountDownLatch(1), done = new CountDownLatch(1);
        CountDownLatch buffers = new CountDownLatch(80);
        AtomicLong expected = new AtomicLong(), last = new AtomicLong();
        AtomicBoolean error = new AtomicBoolean();
        AtomicInteger delivered = new AtomicInteger();
        PosePipeline pipeline = new PosePipeline(context, new PosePipeline.Listener() {
            @Override public void onReady() { ready.countDown(); }
            @Override public void onPose(PoseReading reading, Bitmap image) {
                if (last.getAndSet(reading.frameId) >= reading.frameId) error.set(true);
                delivered.incrementAndGet();
                if (reading.frameId == expected.get()) done.countDown();
            }
            @Override public void onModelError() { error.set(true); ready.countDown(); done.countDown(); }
        });
        try {
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            byte[] image = new byte[320 * 240 * 3 / 2];
            for (int i = 0; i < 80; i++) {
                Frame frame = new Frame(image, 320, 240, 0, i * 33, buffers::countDown);
                if (i == 79) expected.set(frame.id);
                pipeline.offer(frame);
            }
            assertTrue("No result for last frame", done.await(20, TimeUnit.SECONDS));
            assertFalse(error.get());
            assertTrue("Old frames accumulated in the queue", delivered.get() < 10);
            assertEquals(expected.get(), last.get());
            assertTrue("Camera buffers were not released", buffers.await(5, TimeUnit.SECONDS));
        } finally {
            pipeline.close();
        }
    }

    @Test public void drawsThePairedCameraImageWithCorrectRotationAndMirror() {
        byte[] nv21 = {16, 50, 100, (byte) 235, 16, 50, 100, (byte) 235,
                (byte) 128, (byte) 128, (byte) 128, (byte) 128};
        PairedPreview preview = new PairedPreview();
        for (int rotation = 0; rotation < 360; rotation += 90) {
            Frame frame = new Frame(nv21, 4, 2, rotation, 0, () -> {});
            FrameTransform transform = new FrameTransform(4, 2, rotation, 192, 1, 2, 3);
            Bitmap image = preview.copy(frame, transform);
            assertEquals(transform.uprightWidth, image.getWidth());
            assertEquals(transform.uprightHeight, image.getHeight());
            assertEquals(transform.sensorIndex(0, 0) % 4 == 3 ? 255 : 0, image.getPixel(0, 0) & 255);
            instrumentation().runOnMainSync(() -> {
                SkeletonView view = new SkeletonView(instrumentation().getTargetContext(), null);
                view.layout(0, 0, image.getWidth(), image.getHeight());
                PoseReading reading = new PoseReading(null, null, transform, frame, 0, 0, 0, 0, 0);
                view.show(reading, image, true);
                Bitmap output = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(output));
                for (int y = 0; y < output.getHeight(); y++) {
                    for (int x = 0; x < output.getWidth(); x++) {
                        int expected = image.getPixel(output.getWidth() - 1 - x, y) & 255;
                        int actual = output.getPixel(x, y) & 255;
                        // Canvas Android 4.4 интерполирует RGB565 с точностью до одного шага.
                        assertEquals(expected, actual, 8);
                    }
                }
                output.recycle();
            });
            image.recycle();
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
