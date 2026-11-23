package com.example.fitcounter;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Diagnostics {
    private static final int SEGMENT_LIMIT = 512 * 1024;
    private static final int SEGMENTS = 8;
    @SuppressLint("StaticFieldLeak") // Хранится только контекст процесса.
    private static Diagnostics instance;
    final long started = System.currentTimeMillis(), startedUptime = SystemClock.uptimeMillis();
    final String id = started + "-" + UUID.randomUUID();
    final DiagnosticsMetrics metrics = new DiagnosticsMetrics();
    final Context context;
    final Handler disk;
    final Handler ui = new Handler(Looper.getMainLooper());
    private final File directory;
    private final ArrayDeque<Event> queue = new ArrayDeque<>();
    private final AtomicInteger errors = new AtomicInteger();
    private boolean draining;
    private int skipped, segment;
    private long segmentBytes;
    private long nextSample, nextDetail, previousCpu, previousSample, slowSince, lastMemorySample;
    private boolean slow, delayed, stalled, badStorage, firstPerson;
    private volatile String lastError = "";
    private volatile long writeNanos = -1;
    private volatile int writeEvents;
    private volatile JSONObject workout = new JSONObject(), camera = new JSONObject(), device = new JSONObject();
    private volatile JSONObject lastMeasurement = new JSONObject();
    private volatile JSONObject memory = new JSONObject();
    volatile boolean detailed;
    private volatile PoseReading reading;
    private final long[] jointTimes = new long[Pose.SIZE];
    private volatile long lastPerson;
    private String lastBlock = "", lastValidation = "";
    private long blockSince, validationSince;
    private long trackingLossSince = -1;
    private final ArrayDeque<Trend> trends = new ArrayDeque<>();

    static synchronized Diagnostics get(Context context) {
        if (instance == null) instance = new Diagnostics(context.getApplicationContext());
        return instance;
    }

    private Diagnostics(Context context) {
        this.context = context;
        directory = new File(context.getFilesDir(), "diagnostics");
        HandlerThread thread = new HandlerThread("fitcounter-diagnostics", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        disk = new Handler(thread.getLooper());
        disk.post(() -> {
            prepareDirectory();
            device = collectDevice();
            log("INFO", "DEVICE", "DEVICE_INFO", "Конфигурация устройства", 0, () -> device);
        });
        log("INFO", "APP", "APP_STARTED", "Запуск FitCounter", 0, this::appInfo);
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        if (original != null) Thread.setDefaultUncaughtExceptionHandler((threadWithError, failure) -> {
            try {
                // Последняя короткая запись на аварийном потоке. Очередь может уже не обработаться.
                String origin = "";
                for (StackTraceElement frame : failure.getStackTrace()) {
                    if (frame.getClassName().startsWith("com.example.fitcounter.")) {
                        origin = frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                        break;
                    }
                }
                String site = origin;
                Event event = new Event("ERROR", "ERROR", "UNCAUGHT_EXCEPTION", failure.getClass().getName(),
                        0, () -> json("app", appInfo(), "origin", site));
                if (directory.isDirectory()) {
                    try (FileOutputStream output = new FileOutputStream(new File(directory, id + ".crash.jsonl"))) {
                        output.write(event.line(id));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Первичный обработчик сохраняет штатное поведение Android.
            } finally {
                original.uncaughtException(threadWithError, failure);
            }
        });
    }

    void log(String level, String category, String code, String message) {
        log(level, category, code, message, 0, null);
    }

    void log(String level, String category, String code, String message, long frame, Details details) {
        enqueue(new Event(level, category, code, message, frame, details));
    }

    void error(String category, String code, Throwable failure) {
        log("ERROR", category, code, failure == null ? code : failure.getClass().getName());
    }

    private void enqueue(Event event) {
        if ("ERROR".equals(event.level)) {
            lastError = event.code;
            errors.incrementAndGet();
        }
        synchronized (queue) {
            if (queue.size() == 256) {
                if ("DEBUG".equals(event.level)) {
                    skipped++;
                    return;
                }
                queue.removeFirst();
                skipped++;
            }
            queue.addLast(event);
            if (!draining) {
                draining = true;
                disk.postDelayed(this::drain, 180);
            }
        }
    }

    void drain() {
        ArrayDeque<Event> batch;
        int lost;
        synchronized (queue) {
            batch = new ArrayDeque<>(queue);
            queue.clear();
            draining = false;
            lost = skipped;
            skipped = 0;
        }
        if (lost != 0) batch.addFirst(new Event("WARNING", "APP", "LOG_BUFFER_OVERFLOW",
                "Пропущено записей: " + lost, 0, null));
        if (batch.isEmpty()) return;
        long writeStarted = System.nanoTime();
        int eventCount = batch.size();
        try {
            prepareDirectory();
            while (!batch.isEmpty()) {
                File file = new File(directory, id + "." + String.format(java.util.Locale.US, "%06d", segment)
                        + ".jsonl");
                byte[] line;
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(file, true))) {
                    while (!batch.isEmpty()) {
                        line = batch.peekFirst().line(id);
                        if (line.length > SEGMENT_LIMIT) {
                            batch.removeFirst();
                            continue;
                        }
                        if (segmentBytes + line.length > SEGMENT_LIMIT) break;
                        output.write(line);
                        segmentBytes += line.length;
                        batch.removeFirst();
                    }
                }
                if (!batch.isEmpty()) {
                    segment++;
                    segmentBytes = 0;
                }
            }
            rotate();
            badStorage = false;
        } catch (IOException | RuntimeException e) {
            if (!badStorage) {
                errors.incrementAndGet();
                Log.e("FitCounter", "Diagnostics storage", e);
            }
            lastError = "LOG_WRITE_FAILED";
            badStorage = true;
        } finally {
            writeNanos = System.nanoTime() - writeStarted;
            writeEvents = eventCount - batch.size();
        }
    }

    private void prepareDirectory() { if (directory.mkdirs()) rotate(); }

    File[] files() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> {
            int byTime = Long.compare(a.lastModified(), b.lastModified());
            return byTime != 0 ? byTime : a.getName().compareTo(b.getName());
        });
        return files;
    }

    private void rotate() {
        File[] files = files();
        for (int i = 0; i < files.length - SEGMENTS; i++) files[i].delete();
    }

    interface EventReader { void read(JSONObject event, String raw) throws IOException; }

    void read(File[] files, String session, EventReader reader) throws IOException {
        for (File file : files) {
            if (session != null && !file.getName().startsWith(session + ".")) continue;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    try { reader.read(new JSONObject(line), line); }
                    catch (JSONException ignored) { /* Незавершённая строка после отключения питания. */ }
                }
            }
        }
    }

    interface History { void show(String[] sessions, String selected, String lines); }

    void history(String selected, boolean attempts, History callback) {
        disk.post(() -> {
            drain();
            File[] files = files();
            LinkedHashSet<String> sessions = new LinkedHashSet<>();
            sessions.add(id);
            for (int i = files.length - 1; i >= 0; i--) sessions.add(files[i].getName().split("\\.")[0]);
            String session = sessions.contains(selected) ? selected : id;
            ArrayDeque<String> tail = new ArrayDeque<>();
            try {
                read(files, session, (event, line) -> {
                    String code = event.optString("code");
                    if (attempts ? !code.equals("REP_COMPLETED") && !code.equals("REP_REJECTED")
                            : code.equals("FRAME_DETAILS") || code.equals("PERFORMANCE_SAMPLE")) return;
                    if (tail.size() == 35) tail.removeFirst();
                    tail.addLast(DeveloperPanel.date(event.optLong("timestamp_ms")) + " "
                            + event.optString("level") + " " + event.optString("category") + " "
                            + code + "\n" + event.optString("message")
                            + (event.has("frame_id") ? " · кадр " + event.optLong("frame_id") : "")
                            + (event.has("details") ? "\n" + event.optJSONObject("details") : ""));
                });
            } catch (IOException e) { error("APP", "LOG_READ_FAILED", e); }
            StringBuilder result = new StringBuilder();
            while (!tail.isEmpty()) result.append(tail.removeLast()).append("\n\n");
            ui.post(() -> callback.show(sessions.toArray(new String[0]), session, result.toString()));
        });
    }

    void clear(Runnable completed) {
        disk.post(() -> {
            drain();
            boolean ok = true;
            for (File file : files()) ok &= file.delete();
            ok &= DiagnosticsExport.clearReports(context);
            segment++;
            segmentBytes = 0;
            metrics.reset();
            synchronized (trends) { trends.clear(); }
            errors.set(0);
            lastError = "";
            writeNanos = -1;
            writeEvents = 0;
            if (!ok) error("APP", "DIAGNOSTICS_CLEAR_FAILED", null);
            log("INFO", "APP", "DIAGNOSTICS_CLEARED", "Очистка диагностики", 0, this::appInfo);
            ui.post(() -> {
                lastMeasurement = new JSONObject();
                lastPerson = 0;
                Arrays.fill(jointTimes, 0);
                completed.run();
            });
        });
    }

    void camera(String code, String message, JSONObject values) {
        JSONObject snapshot = merge(camera, values);
        camera = snapshot;
        log("INFO", "CAMERA", code, message, 0, () -> snapshot);
    }

    JSONObject cameraInfo() { return camera; }
    JSONObject deviceInfo() { return device; }
    JSONObject memoryInfo() { return memory; }
    int errors() { return errors.get(); }
    String lastError() { return lastError; }
    JSONObject exerciseInfo() { return workout; }
    JSONObject lastMeasurement() { return lastMeasurement; }
    void offered(Frame frame, boolean discarded, int depth) { metrics.offer(frame, discarded, depth); }
    void processing(int depth) { metrics.start(depth); }
    void measurementDropped() { metrics.droppedMeasurement(); }
    void pipelineStopped() { metrics.stopped(); }

    void processed(PoseReading frame, long copy) { metrics.finish(frame, copy); }

    void sample() {
        long now = SystemClock.uptimeMillis();
        if (now < nextSample) return;
        nextSample = now + 1000;
        disk.post(() -> {
            long time = SystemClock.uptimeMillis();
            if (time - lastMemorySample >= 4000 || lastMemorySample == 0) {
                memory = collectMemory();
                lastMemorySample = time;
            }
            JSONObject data = memory;
            DiagnosticsMetrics.Data m = metrics.snapshot();
            synchronized (trends) {
                while (!trends.isEmpty() && trends.peekFirst().time < time - 60_000) trends.removeFirst();
                trends.addLast(new Trend(time, m.model, m.inference.p50 / 1e6, m.age / 1e6,
                        data.optLong("process_pss_kb") / 1024.0));
            }
            if (m.camera == 0 && m.model == 0) {
                slowSince = 0;
                return;
            }
            JSONObject row = json("camera_fps", m.camera, "model_fps", m.model, "overlay_fps", m.overlay,
                    "preview_fps", m.preview, "inference_p50_ms", ms(m.inference.p50),
                    "inference_p95_ms", ms(m.inference.p95), "callback_to_render_p95_ms", ms(m.latency.p95),
                    "result_age_ms", ms(m.age), "queue", m.queue, "dropped", m.dropped,
                    "process_pss_kb", data.optLong("process_pss_kb"),
                    "cpu_one_core_percent", data.opt("cpu_one_core_percent"));
            log("DEBUG", "PERFORMANCE", "PERFORMANCE_SAMPLE", "Секундная выборка", 0, () -> row);
            if (m.camera >= 5 && m.model >= 0 && m.model < 4) {
                if (slowSince == 0) slowSince = time;
                if (!slow && time - slowSince >= 3000) {
                    slow = true;
                    log("WARNING", "PERFORMANCE", "LOW_MODEL_FPS", "Обработка ниже 4 кадр/с", 0, () -> row);
                }
            } else if (m.model >= 5 || m.camera == 0) {
                slow = false;
                slowSince = 0;
            }
            if (!delayed && m.latency.count >= 10 && m.latency.p95 > 300_000_000) {
                delayed = true;
                log("WARNING", "PERFORMANCE", "HIGH_OVERLAY_LATENCY", "Callback → Canvas p95 выше 300 мс",
                        0, () -> row);
            } else if (m.latency.p95 < 250_000_000) delayed = false;
            if (m.uiDelay >= 100 && !stalled) {
                stalled = true;
                log("WARNING", "UI", "UI_STALL", "Задержка основного потока " + m.uiDelay + " мс");
            } else if (m.uiDelay < 50) stalled = false;
        });
    }

    Trend[] trends() {
        synchronized (trends) { return trends.toArray(new Trend[0]); }
    }

    static final class Trend {
        final long time;
        final double[] values;
        Trend(long time, double... values) { this.time = time; this.values = values; }
    }

    void checkpoint(WorkoutSession s, String code) {
        workout = workout(s, null);
        JSONObject snapshot = workout;
        String category = code.equals("REP_REJECTED") ? "COUNTER" : code.startsWith("TRACKING_")
                ? "TRACKING" : "EXERCISE";
        String message = code.equals("REP_REJECTED") ? s.rejection() : code;
        log("INFO", category, code, message, 0, () -> snapshot);
    }

    void track(WorkoutSession s, PoseReading r, int reps, int rejected, TrackingStatus.State prior,
               ExerciseCounter.Phase phase) {
        long begin = System.nanoTime();
        reading = r;
        if (r.pose != null) {
            lastPerson = r.time;
            for (int i = 0; i < Pose.SIZE; i++) {
                if (r.candidate.visible(i, PoseValidator.DRAW_CONFIDENCE)) jointTimes[i] = r.time;
            }
        }
        String block = s.blocking();
        boolean detail = detailed && r.time >= nextDetail;
        JSONObject snapshot = workout(s, r);
        workout = snapshot;
        lastMeasurement = snapshot;
        if (!firstPerson && r.pose != null) {
            firstPerson = true;
            log("INFO", "POSE", "PERSON_FIRST_CONFIRMED", r.validation, r.frameId, null);
        }
        if (prior != s.tracking()) {
            String code = s.tracking() == TrackingStatus.State.TRACKING ? "TRACKING_READY"
                    : "TRACKING_WARNING_CHANGED";
            log("INFO", "TRACKING", code, prior + " → " + s.tracking() + " / " + r.validation,
                    r.frameId, () -> snapshot);
        }
        long missingSince = s.missingSince();
        if (trackingLossSince >= 0 && missingSince < 0) {
            log("INFO", "TRACKING", "TRACKING_RESTORED",
                    "Длительность потери " + (r.time - trackingLossSince) + " мс", r.frameId, () -> snapshot);
        } else if (trackingLossSince < 0 && missingSince >= 0 && prior == TrackingStatus.State.TRACKING) {
            log("INFO", "TRACKING", "TRACKING_LOST", block, r.frameId, () -> snapshot);
        }
        trackingLossSince = missingSince;
        if (!r.validation.equals(lastValidation)) {
            String message = lastValidation + " " + (r.time - validationSince) + " мс → " + r.validation;
            if (!lastValidation.isEmpty()) log("DEBUG", "POSE", "POSE_VALIDATION_CHANGED", message, r.frameId, null);
            lastValidation = r.validation;
            validationSince = r.time;
        }
        if (!block.equals(lastBlock)) {
            String reason = lastBlock.isEmpty() ? block : lastBlock + " " + (r.time - blockSince) + " мс → " + block;
            log("DEBUG", "ANGLES", "COUNTING_CONDITION_CHANGED", reason, r.frameId, () -> snapshot);
            lastBlock = block;
            blockSince = r.time;
        }
        if (reps != s.repetitions()) {
            log("INFO", "COUNTER", "REP_COMPLETED", s.exercise().name(), r.frameId, () -> snapshot);
        } else if (phase != s.phase()) {
            String code;
            switch (s.phase()) {
                case LOWERING: code = "REP_DESCENDING"; break;
                case BOTTOM: code = "REP_BOTTOM_REACHED"; break;
                case RISING: code = "REP_ASCENDING"; break;
                case READY: code = phase == ExerciseCounter.Phase.FIND_START
                        ? "START_POSITION_CONFIRMED" : "REP_RETURNED_WITHOUT_COUNT"; break;
                default: code = "WAITING_FOR_START_POSITION";
            }
            log("INFO", "EXERCISE", code, phase + " → " + s.phase(), r.frameId, () -> snapshot);
        }
        if (rejected != s.rejected()) {
            log("INFO", "COUNTER", "REP_REJECTED", s.rejection(), r.frameId, () -> snapshot);
        }
        if (detail) {
            nextDetail = r.time + 250;
            long[] times = jointTimes.clone();
            log("DEBUG", "POSE", "FRAME_DETAILS", r.validation, r.frameId,
                    () -> json("pose", poseInfo(r, times), "exercise", snapshot,
                            "preprocess_ms", ms(r.preprocessNanos), "inference_ms", ms(r.inferenceNanos),
                            "postprocess_ms", ms(r.postprocessNanos), "filter_ms", ms(r.filterNanos),
                            "angles_ms", ms(r.angleNanos), "queue_ms", ms(r.queueNanos)));
        }
        metrics.loggingCost(System.nanoTime() - begin);
    }

    void stopTracking() {
        PoseReading previous = reading;
        if (previous != null) {
            JSONObject snapshot = lastMeasurement;
            log("INFO", "TRACKING", "LAST_MEASUREMENT", lastBlock + " · "
                    + (previous.time - blockSince) + " мс по кадрам", previous.frameId, () -> snapshot);
        }
        reading = null;
        Arrays.fill(jointTimes, 0);
        lastValidation = lastBlock = "";
        trackingLossSince = -1;
    }

    JSONObject poseInfo() { return poseInfo(reading, jointTimes.clone()); }

    long lastPerson() { return lastPerson; }

    boolean person() { return reading != null && reading.pose != null; }

    private JSONObject poseInfo(PoseReading r, long[] times) {
        if (r == null) return json("person", JSONObject.NULL, "status", "NO_CURRENT_RESULT",
                "last_success_uptime_ms", lastPerson);
        JSONArray joints = new JSONArray();
        int visible = 0;
        for (int i = 0; i < Pose.SIZE; i++) {
            boolean valid = r.pose != null && r.candidate.visible(i, PoseValidator.DRAW_CONFIDENCE);
            if (valid) visible++;
            joints.put(json("joint", PoseReading.JOINTS[i], "x", number(r.candidate == null ? Double.NaN : r.candidate.x[i]),
                    "y", number(r.candidate == null ? Double.NaN : r.candidate.y[i]),
                    "confidence", number(r.modelConfidence == null ? Double.NaN : r.modelConfidence[i]),
                    "valid", valid, "last_valid_uptime_ms", times[i] == 0 ? JSONObject.NULL : times[i]));
        }
        return json("person", r.pose != null, "confirmed_poses", r.pose == null ? 0 : 1,
                "person_confidence", JSONObject.NULL, "pose_confidence", JSONObject.NULL,
                "validation", r.validation, "visible_joints", visible, "invalid_joints", Pose.SIZE - visible,
                "frame_id", r.frameId, "inference_ms", ms(r.inferenceNanos),
                "last_success_uptime_ms", lastPerson, "coordinate_space", "upright_pixels",
                "upright_width", r.transform.uprightWidth, "upright_height", r.transform.uprightHeight,
                "crop_size_px", r.transform.cropSize, "full_frame_retry", r.fullFrameRetry,
                "joints", joints);
    }

    private static JSONObject workout(WorkoutSession s, PoseReading r) {
        return json("exercise", s.exercise().name(), "workout_active", s.running(), "pending_save", s.pending(),
                "start_confirmed", s.phase() != ExerciseCounter.Phase.FIND_START,
                "counting_enabled", s.running() && !Double.isNaN(s.angle())
                        && s.phase() != ExerciseCounter.Phase.FIND_START,
                "phase", s.phase().name(), "previous_phase", s.previousPhase().name(),
                "transition_uptime_ms", s.phaseTime(), "angle_degrees", number(s.angle()),
                "upper_degrees", s.upper(), "lower_degrees", s.lower(),
                "side", s.side() == 0 ? "LEFT" : "RIGHT", "direction", s.direction(),
                "repetitions", s.repetitions(), "last_rep_uptime_ms", s.repTime(), "rejected", s.rejected(),
                "condition", s.blocking(), "last_rejection", s.rejection(), "tracking", s.tracking().name(),
                "tracking_detail", s.tracking() == TrackingStatus.State.TRACKING && s.missingSince() >= 0
                        ? "TEMPORARILY_LOST" : s.tracking().name(),
                "loss_ms", s.missingSince() < 0 ? 0 : (r == null ? SystemClock.uptimeMillis() : r.time) - s.missingSince(),
                "left_measurement", r == null ? JSONObject.NULL : r.limbReasons[s.exercise().ordinal()][0],
                "right_measurement", r == null ? JSONObject.NULL : r.limbReasons[s.exercise().ordinal()][1],
                "left_angle_degrees", limbAngle(s, r, 0), "right_angle_degrees", limbAngle(s, r, 1),
                "frame_id", r == null ? JSONObject.NULL : r.frameId);
    }

    private static Object limbAngle(WorkoutSession s, PoseReading r, int side) {
        PoseReading.Limb limb = r == null ? null : r.limbs[s.exercise().ordinal()][side];
        return limb == null ? JSONObject.NULL : number(limb.angle);
    }

    JSONObject appInfo() {
        return json("version", BuildConfig.VERSION_NAME, "version_code", BuildConfig.VERSION_CODE,
                "session_id", id, "started_epoch_ms", started, "compile_sdk", 37, "min_sdk", 19,
                "target_sdk", context.getApplicationInfo().targetSdkVersion, "model", BuildConfig.POSE_MODEL,
                "runtime", BuildConfig.POSE_RUNTIME);
    }

    JSONObject summary() {
        return json("app", appInfo(), "updated_epoch_ms", System.currentTimeMillis(),
                "duration_ms", SystemClock.uptimeMillis() - startedUptime, "error_count", errors(),
                "last_error", lastError(), "exercise", exerciseInfo(), "last_measurement", lastMeasurement(),
                "performance", performance());
    }

    JSONObject performance() {
        DiagnosticsMetrics.Data m = metrics.snapshot();
        boolean processed = m.inference.count > 0;
        return json("camera_fps", fps(m.camera), "model_fps", fps(m.model), "overlay_fps", fps(m.overlay),
                "texture_updates_fps", fps(m.preview), "inference_min_ms", ms(m.inference.min),
                "inference_mean_ms", ms(m.inference.mean), "inference_max_ms", ms(m.inference.max),
                "inference_p50_ms", ms(m.inference.p50), "inference_p95_ms", ms(m.inference.p95),
                "preprocess_ms", ms(processed ? m.preprocess : -1), "postprocess_ms", ms(processed ? m.postprocess : -1),
                "filter_ms", ms(processed ? m.filter : -1), "angles_ms", ms(processed ? m.angles : -1),
                "preview_copy_ms", ms(m.copy == 0 ? -1 : m.copy), "draw_ms", ms(m.latency.count == 0 ? -1 : m.draw),
                "callback_to_inference_ms", ms(processed ? m.toInference : -1),
                "callback_to_render_p50_ms", ms(m.latency.p50), "callback_to_render_p95_ms", ms(m.latency.p95),
                "result_age_ms", ms(m.age), "ui_heartbeat_delay_ms", m.uiDelay, "last_ui_stall_ms", m.lastStall,
                "diagnostics_update_ms", ms(m.updateCost), "log_last_batch_ms", ms(writeNanos),
                "log_last_batch_events", writeEvents, "model_worker_busy", m.busy, "pending_frames", m.queue,
                "discarded_frames", m.dropped, "discarded_measurements", m.deliveryDrops,
                "stale_overlays", m.stale, "memory_and_cpu", memoryInfo());
    }

    JSONObject cameraMetrics() {
        DiagnosticsMetrics.Data m = metrics.snapshot();
        return json("configuration", cameraInfo(), "fps", fps(m.camera),
                "last_callback_uptime_ms", m.lastCallback == 0 ? JSONObject.NULL : m.lastCallback,
                "received", m.received, "discarded", m.dropped, "forwarded_to_model", m.forwarded,
                "processed", m.processed, "sensor_capture_latency_ms", JSONObject.NULL);
    }

    private JSONObject collectDevice() {
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        StatFs storage = new StatFs(context.getFilesDir().getPath());
        JSONArray abis = new JSONArray();
        if (Build.VERSION.SDK_INT >= 21) {
            for (String abi : Build.SUPPORTED_ABIS) abis.put(abi);
        } else {
            abis.put(Build.CPU_ABI);
            if (!Build.CPU_ABI2.isEmpty() && !"unknown".equals(Build.CPU_ABI2)) abis.put(Build.CPU_ABI2);
        }
        return json("manufacturer", Build.MANUFACTURER, "model", Build.MODEL, "device", Build.DEVICE,
                "android", Build.VERSION.RELEASE, "api", Build.VERSION.SDK_INT, "build_fingerprint", Build.FINGERPRINT,
                "abi", abis, "process_arch", System.getProperty("os.arch"),
                "cpu_cores", Runtime.getRuntime().availableProcessors(), "screen_width_px", display.widthPixels,
                "screen_height_px", display.heightPixels, "density_dpi", display.densityDpi,
                "screen_width_dp", context.getResources().getConfiguration().screenWidthDp,
                "screen_height_dp", context.getResources().getConfiguration().screenHeightDp,
                "available_storage_bytes", storage.getAvailableBytes(), "tango_sdk", false,
                "camera_api", "android.hardware.Camera (Camera1)", "memory_at_start", collectMemory());
    }

    void display(Context current, int degrees) {
        DisplayMetrics metrics = new DisplayMetrics();
        ((android.view.WindowManager) current.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getRealMetrics(metrics);
        boolean landscape = current.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        disk.post(() -> device = merge(device, json("display_rotation_degrees", degrees,
                "orientation", landscape ? "LANDSCAPE" : "PORTRAIT",
                "screen_width_px", metrics.widthPixels, "screen_height_px", metrics.heightPixels)));
    }

    private JSONObject collectMemory() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        long now = SystemClock.uptimeMillis(), cpu = Process.getElapsedCpuTime();
        Object usage = previousSample == 0 || now <= previousSample ? JSONObject.NULL
                : Math.round(1000.0 * (cpu - previousCpu) / (now - previousSample)) / 10.0;
        previousSample = now;
        previousCpu = cpu;
        return json("system_total_bytes", info.totalMem, "system_available_bytes", info.availMem,
                "system_low_memory", info.lowMemory, "process_pss_kb", Debug.getPss(),
                "cpu_one_core_percent", usage);
    }

    static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(object, (String) pairs[i], pairs[i + 1]);
        return object;
    }

    private static JSONObject merge(JSONObject base, JSONObject update) {
        JSONObject result = new JSONObject();
        java.util.Iterator<String> keys = base.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            put(result, key, base.opt(key));
        }
        keys = update.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            put(result, key, update.opt(key));
        }
        return result;
    }

    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value == null ? JSONObject.NULL : value); }
        catch (JSONException e) { throw new IllegalArgumentException(key, e); }
    }

    static Object number(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? JSONObject.NULL : Math.round(value * 1000) / 1000.0;
    }
    static Object ms(long nanos) { return nanos < 0 ? JSONObject.NULL : number(nanos / 1_000_000.0); }
    static Object fps(double rate) { return rate < 0 ? JSONObject.NULL : number(rate); }

    interface Details { JSONObject data(); }

    private static final class Event {
        final long timestamp = System.currentTimeMillis(), frame;
        final String level, category, code, message;
        final Details details;

        Event(String level, String category, String code, String message, long frame, Details details) {
            this.level = level;
            this.category = category;
            this.code = code;
            this.message = message;
            this.frame = frame;
            this.details = details;
        }

        byte[] line(String id) {
            JSONObject event = json("timestamp_ms", timestamp, "level", level, "category", category,
                    "code", code, "message", message, "session_id", id);
            if (frame != 0) put(event, "frame_id", frame);
            if (details != null) put(event, "details", details.data());
            return (event + "\n").getBytes(StandardCharsets.UTF_8);
        }
    }
}
