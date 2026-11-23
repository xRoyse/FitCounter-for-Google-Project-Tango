package com.example.fitcounter;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DiagnosticsInstrumentedTest {
    @Test public void deliversMeasurementsInOrderEvenWhenTheOverlayIsTooOld() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch ready = new CountDownLatch(1), blocked = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1), shown = new CountDownLatch(1);
        List<Long> measurements = new ArrayList<>(), display = new ArrayList<>();
        List<Long> expected = new ArrayList<>();
        PosePipeline pipeline = new PosePipeline(context, new PosePipeline.Listener() {
            @Override public void onReady() { ready.countDown(); }
            @Override public void onMeasurement(PoseReading reading) { measurements.add(reading.frameId); }
            @Override public void onPose(PoseReading reading, Bitmap image) {
                display.add(reading.frameId);
                shown.countDown();
            }
            @Override public void onModelError() { shown.countDown(); }
        });
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            new Handler(Looper.getMainLooper()).post(() -> {
                blocked.countDown();
                try { unblock.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(3, TimeUnit.SECONDS));
            byte[] image = new byte[320 * 240 * 3 / 2];
            for (int i = 0; i < 3; i++) {
                CountDownLatch processed = new CountDownLatch(1);
                Frame frame = new Frame(image, 320, 240, 0, SystemClock.uptimeMillis(), processed::countDown);
                expected.add(frame.id);
                pipeline.offer(frame);
                assertTrue(processed.await(1, TimeUnit.SECONDS));
            }
            SystemClock.sleep(PosePipeline.MAX_DISPLAY_AGE_MS + 20);
            unblock.countDown();
            assertTrue(shown.await(3, TimeUnit.SECONDS));
            assertEquals(expected, measurements);
            assertEquals(Arrays.asList(expected.get(2)), display);
        } finally {
            unblock.countDown();
            pipeline.close();
        }
    }

    @Test public void opensOnlyAfterFiveQuickStatisticsTapsAndClosesWithBack() {
        Instrumentation test = InstrumentationRegistry.getInstrumentation();
        MainActivity activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(),
                MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            View stats = activity.findViewById(R.id.tab_stats);
            test.runOnMainSync(() -> {
                assertTrue(stats.performClick());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.stats_screen).getVisibility());
                assertFalse(activity.developerPanelShowing());
            });
            SystemClock.sleep(2600);
            test.runOnMainSync(() -> {
                for (int i = 0; i < 4; i++) assertTrue(stats.performClick());
                assertFalse(activity.developerPanelShowing());
                assertTrue(stats.performClick());
                assertTrue(activity.developerPanelShowing());
            });
            test.waitForIdleSync();
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                assertFalse(activity.developerPanelShowing());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.stats_screen).getVisibility());
            });
        } finally {
            test.runOnMainSync(activity::finish);
        }
    }

    @Test public void writesValidZipAndSharesReadOnlyContentWhileRespectingDetailMode() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Diagnostics diagnostics = Diagnostics.get(context);
        boolean oldDetail = diagnostics.detailed;
        File basic = null, verbose = null;
        File saved = new File(context.getCacheDir(), "diagnostic-test-document.zip");
        try {
            diagnostics.log("DEBUG", "POSE", "FRAME_DETAILS", "Тест", 42,
                    () -> Diagnostics.json("test_coordinate", 123));
            diagnostics.detailed = false;
            basic = export(diagnostics);
            try (ZipFile archive = new ZipFile(basic)) {
                assertEquals(10, archive.size());
                assertNotNull(archive.getEntry("FitCounter-Diagnostics/exercise-events.json"));
                assertNotNull(archive.getEntry("FitCounter-Diagnostics/performance.csv"));
                assertNull(archive.getEntry("FitCounter-Diagnostics/frame-details.jsonl"));
            }
            Uri uri = DiagnosticsProvider.uri(context, basic);
            assertEquals("content", uri.getScheme());
            assertEquals("application/zip", context.getContentResolver().getType(uri));
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                assertEquals('P', stream.read());
                assertEquals('K', stream.read());
            }
            CountDownLatch copy = new CountDownLatch(1);
            AtomicReference<Boolean> copied = new AtomicReference<>(false);
            DiagnosticsExport.save(diagnostics, basic, Uri.fromFile(saved), ok -> {
                copied.set(ok);
                copy.countDown();
            });
            assertTrue(copy.await(20, TimeUnit.SECONDS));
            assertTrue(copied.get());
            assertEquals(basic.length(), saved.length());
            diagnostics.detailed = true;
            verbose = export(diagnostics);
            try (ZipFile archive = new ZipFile(verbose)) {
                assertEquals(11, archive.size());
                assertTrue(archive.getEntry("FitCounter-Diagnostics/frame-details.jsonl").getSize() > 0);
            }
        } finally {
            diagnostics.detailed = oldDetail;
            if (basic != null) basic.delete();
            if (verbose != null) verbose.delete();
            saved.delete();
        }
    }

    private static File export(Diagnostics diagnostics) throws InterruptedException {
        AtomicReference<File> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        DiagnosticsExport.create(diagnostics, file -> {
            result.set(file);
            done.countDown();
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertNotNull(result.get());
        return result.get();
    }
}
