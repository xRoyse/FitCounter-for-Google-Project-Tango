package com.example.fitcounter;

import android.content.Context;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

@RunWith(AndroidJUnit4.class)
public final class PoseMediaTest {
    @Test public void replaysAnnotatedMedia() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("media");
        assumeNotNull(path);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject report = new JSONObject();
        JSONArray summaries = new JSONArray(), errors = new JSONArray();
        report.put("fixtures", summaries).put("errors", errors)
                .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT);
        try (ZipFile archive = new ZipFile(path); PoseEstimator estimator = PoseEstimator.open(target);
                InputStream manifest = archive.getInputStream(archive.getEntry("manifest.json"))) {
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int size;
            while ((size = manifest.read(chunk)) != -1) json.write(chunk, 0, size);
            JSONArray fixtures = new JSONArray(json.toString("UTF-8"));
            report.put("estimator", estimator.getClass().getSimpleName());
            for (int i = 0; i < fixtures.length(); i++) {
                JSONObject fixture = fixtures.getJSONObject(i);
                String id = fixture.getString("id");
                try (VideoFrameSource source = new VideoFrameSource(
                        archive.getInputStream(archive.getEntry(id + ".nv21")), fixture.getInt("width"),
                        fixture.getInt("height"), fixture.getInt("fps"))) {
                    summaries.put(replay(target, estimator, source, fixture, errors));
                }
            }
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                target.openFileOutput("pose-media-report.json", Context.MODE_PRIVATE), StandardCharsets.UTF_8)) {
            writer.write(report.toString(2));
        }
        assertTrue(errors.toString(), errors.length() == 0);
    }

    private static JSONObject replay(Context target, PoseEstimator estimator, VideoFrameSource source,
                                     JSONObject fixture, JSONArray errors) throws Exception {
        String id = fixture.getString("id");
        Exercise exercise = Exercise.valueOf(fixture.getString("exercise"));
        PoseAnalyzer analyzer = new PoseAnalyzer(estimator);
        WorkoutSession session = new WorkoutSession(exercise, exercise.straight, exercise.depth, null);
        session.start(0);
        JSONArray frames = new JSONArray(), events = new JSONArray();
        JSONArray loss = fixture.optJSONArray("loss");
        int[] missingMeasurements = new int[loss == null ? 0 : loss.length()];
        int confirmed = 0, warnings = 0;
        TrackingStatus.State lastStatus = session.tracking();
        try (OutputStreamWriter writer = new OutputStreamWriter(
                target.openFileOutput("pose-" + id + ".jsonl", Context.MODE_PRIVATE), StandardCharsets.UTF_8)) {
            Frame frame;
            while ((frame = source.next()) != null) {
                PoseReading reading = analyzer.analyze(frame);
                int before = session.repetitions();
                session.accept(reading);
                if (before != session.repetitions()) events.put(frame.time);
                for (int i = 0; i < missingMeasurements.length; i++) {
                    JSONArray interval = loss.getJSONArray(i);
                    if (frame.time >= interval.getLong(0) && frame.time <= interval.getLong(1)
                            && Double.isNaN(session.angle())) missingMeasurements[i]++;
                }
                if (reading.pose != null) confirmed++;
                TrackingStatus.State status = session.tracking();
                if (status != lastStatus && (status == TrackingStatus.State.NO_PERSON
                        || status == TrackingStatus.State.PARTIALLY_VISIBLE)) warnings++;
                lastStatus = status;
                JSONObject sample = new JSONObject().put("time_ms", frame.time).put("frame_id", frame.id)
                        .put("person", reading.pose != null).put("status", status.name())
                        .put("phase", session.phase().name()).put("count", session.repetitions())
                        .put("angle", Double.isNaN(session.angle()) ? JSONObject.NULL : session.angle())
                        .put("preprocess_us", reading.preprocessNanos / 1000)
                        .put("inference_us", reading.inferenceNanos / 1000)
                        .put("postprocess_us", reading.postprocessNanos / 1000)
                        .put("smoothing_us", reading.filterNanos / 1000)
                        .put("angles_us", reading.angleNanos / 1000);
                JSONArray limbs = new JSONArray();
                for (PoseReading.Limb limb : reading.limbs[exercise.ordinal()]) {
                    limbs.put(limb == null ? JSONObject.NULL : new JSONArray()
                            .put(limb.confidence).put(limb.angle).put(limb.height).put(limb.limbLength)
                            .put(limb.startPosition));
                }
                sample.put("limbs", limbs);
                if (reading.pose != null) {
                    JSONArray confidence = new JSONArray();
                    for (float score : reading.pose.confidence) confidence.put(score);
                    sample.put("confidence", confidence);
                }
                writer.write(sample.toString());
                writer.write('\n');
                frames.put(reading.inferenceNanos / 1000);
            }
        }
        JSONArray intervals = fixture.getJSONArray("reps");
        if (events.length() != intervals.length()) errors.put(id + ": repetitions " + events.length()
                + " != " + intervals.length());
        int next = 0;
        for (int i = 0; i < intervals.length(); i++) {
            JSONArray interval = intervals.getJSONArray(i);
            while (next < events.length() && events.getLong(next) < interval.getLong(0)) {
                errors.put(id + ": unexpected repetition at " + events.getLong(next++));
            }
            if (next < events.length() && events.getLong(next) <= interval.getLong(1)) {
                next++;
            } else {
                errors.put(id + ": missing repetition in " + interval);
            }
        }
        while (next < events.length()) errors.put(id + ": unexpected repetition at " + events.getLong(next++));
        JSONArray lost = new JSONArray();
        for (int i = 0; i < missingMeasurements.length; i++) {
            lost.put(missingMeasurements[i]);
            if (missingMeasurements[i] == 0) errors.put(id + ": no loss during " + loss.getJSONArray(i));
        }
        if (confirmed < fixture.optInt("minConfirmed", 0)
                || confirmed > fixture.optInt("maxConfirmed", Integer.MAX_VALUE)) {
            errors.put(id + ": confirmed poses " + confirmed);
        }
        if (warnings > fixture.optInt("maxWarnings", Integer.MAX_VALUE)) errors.put(id + ": warnings " + warnings);
        if (fixture.has("finalStatus") && !lastStatus.name().equals(fixture.getString("finalStatus"))) {
            errors.put(id + ": final tracking state " + lastStatus);
        }
        long[] latency = new long[frames.length()];
        for (int i = 0; i < latency.length; i++) latency[i] = frames.getLong(i);
        assertTrue(id + ": empty media", latency.length > 0);
        java.util.Arrays.sort(latency);
        return new JSONObject().put("id", id).put("frames", frames.length()).put("confirmed", confirmed)
                .put("warnings", warnings).put("final_status", lastStatus.name())
                .put("loss_intervals_ms", loss == null ? new JSONArray() : loss)
                .put("missing_angle_frames_by_interval", lost)
                .put("repetitions_ms", events).put("expected_completion_ms", intervals)
                .put("inference_p50_us", latency[(latency.length - 1) / 2])
                .put("inference_p95_us", latency[(int) Math.ceil(latency.length * .95) - 1]);
    }
}
