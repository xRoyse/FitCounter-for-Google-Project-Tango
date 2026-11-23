package com.example.fitcounter;

import android.content.Context;
import android.net.Uri;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

final class DiagnosticsExport {
    private static final String ROOT = "FitCounter-Diagnostics/";
    private static final String[] CSV = {"camera_fps", "model_fps", "overlay_fps", "preview_fps",
            "inference_p50_ms", "inference_p95_ms", "callback_to_render_p95_ms", "result_age_ms",
            "queue", "dropped", "process_pss_kb", "cpu_one_core_percent"};

    interface Result { void ready(File file); }

    static void create(Diagnostics d, Result result) {
        boolean detail = d.detailed;
        JSONObject pose = d.poseInfo();
        if (!detail) pose.remove("joints");
        d.disk.post(() -> {
            File file = null;
            try {
                d.drain();
                file = write(d, detail, pose);
            } catch (IOException | RuntimeException e) {
                d.error("APP", "EXPORT_FAILED", e);
            }
            File report = file;
            d.ui.post(() -> result.ready(report));
        });
    }

    interface SaveResult { void saved(boolean ok); }

    static void save(Diagnostics d, File file, Uri target, SaveResult result) {
        d.disk.post(() -> {
            boolean ok = false;
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = d.context.getContentResolver().openOutputStream(target, "wt")) {
                if (output == null) throw new IOException("Document unavailable");
                transfer(input, output);
                ok = true;
            } catch (IOException | RuntimeException e) {
                ok = false;
                d.error("APP", "EXPORT_SAVE_FAILED", e);
            }
            boolean saved = ok;
            d.ui.post(() -> result.saved(saved));
        });
    }

    static File directory(Context context) { return new File(context.getCacheDir(), "diagnostic-reports"); }

    static boolean clearReports(Context context) {
        boolean ok = true;
        for (File file : reports(directory(context))) ok &= file.delete();
        return ok;
    }

    private static File[] reports(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("fitcounter-diagnostics-"));
        return files == null ? new File[0] : files;
    }

    private static File write(Diagnostics d, boolean detail, JSONObject pose) throws IOException {
        File directory = directory(d.context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Export directory unavailable");
        String date = new SimpleDateFormat("yyyy-MM-dd-HHmmss-SSS", Locale.US).format(new Date());
        File finalFile = new File(directory, "fitcounter-diagnostics-" + date + ".zip");
        File temp = new File(directory, finalFile.getName() + ".partial");
        File[] files = d.files();
        LinkedHashMap<String, JSONObject> history = new LinkedHashMap<>();
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
                entry(zip, "device-info.json", d.deviceInfo().toString());
                entry(zip, "app-info.json", d.appInfo().toString());
                entry(zip, "session-summary.json", d.summary().toString());
                entry(zip, "camera-metrics.json", d.cameraMetrics().toString());
                entry(zip, "pose-tracking.json", pose.toString());
                zip.putNextEntry(new ZipEntry(ROOT + "application.log"));
                d.read(files, null, (event, line) -> {
                    String code = event.optString("code");
                    if (!"FRAME_DETAILS".equals(code)) line(zip, line);
                    String id = event.optString("session_id");
                    JSONObject session = history.get(id);
                    if (session == null) {
                        session = Diagnostics.json("session_id", id, "error_count_retained", 0);
                        history.put(id, session);
                    }
                    Diagnostics.put(session, "last_record_epoch_ms", event.optLong("timestamp_ms"));
                    JSONObject details = event.optJSONObject("details");
                    if ("APP_STARTED".equals(code)) Diagnostics.put(session, "app", details);
                    if ("DEVICE_INFO".equals(code)) Diagnostics.put(session, "device", details);
                    if ("PERFORMANCE_SAMPLE".equals(code)) Diagnostics.put(session, "latest_performance", details);
                    if ("ERROR".equals(event.optString("level"))) {
                        Diagnostics.put(session, "error_count_retained", session.optInt("error_count_retained") + 1);
                        Diagnostics.put(session, "last_error", code);
                    }
                    if ("UNCAUGHT_EXCEPTION".equals(code) || "APP_UI_PAUSED".equals(code)) {
                        Diagnostics.put(session, "last_close_event", event);
                    }
                    String category = event.optString("category");
                    if ("COUNTER".equals(category) || "EXERCISE".equals(category)) {
                        Diagnostics.put(session, "exercise", details);
                    }
                });
                zip.closeEntry();
                entry(zip, "session-history.json", new JSONArray(history.values()).toString());
                zip.putNextEntry(new ZipEntry(ROOT + "exercise-events.json"));
                zip.write('[');
                boolean[] first = {true};
                d.read(files, null, (event, raw) -> {
                    String category = event.optString("category");
                    if (!"EXERCISE".equals(category) && !"COUNTER".equals(category)) return;
                    if (!first[0]) zip.write(',');
                    first[0] = false;
                    line(zip, raw);
                });
                zip.write(']');
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(ROOT + "errors.log"));
                d.read(files, null, (event, raw) -> {
                    if ("ERROR".equals(event.optString("level"))) line(zip, raw);
                });
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(ROOT + "performance.csv"));
                StringBuilder header = new StringBuilder("timestamp_ms,session_id");
                for (String key : CSV) header.append(',').append(key);
                line(zip, header.toString());
                d.read(files, null, (event, raw) -> {
                    if (!"PERFORMANCE_SAMPLE".equals(event.optString("code"))) return;
                    JSONObject value = event.optJSONObject("details");
                    StringBuilder row = new StringBuilder().append(event.optLong("timestamp_ms"))
                            .append(',').append(event.optString("session_id"));
                    for (String key : CSV) row.append(',').append(value == null || value.isNull(key)
                            ? "" : value.optString(key));
                    line(zip, row.toString());
                });
                zip.closeEntry();
                if (detail) {
                    zip.putNextEntry(new ZipEntry(ROOT + "frame-details.jsonl"));
                    d.read(files, null, (event, raw) -> {
                        if ("FRAME_DETAILS".equals(event.optString("code"))) line(zip, raw);
                    });
                    zip.closeEntry();
                }
            }
            verify(temp, detail ? 11 : 10);
            if (!temp.renameTo(finalFile) || !finalFile.isFile() || finalFile.length() == 0) {
                throw new IOException("Export could not be finalized");
            }
        } finally {
            if (temp.exists()) temp.delete();
        }
        File[] old = reports(directory);
        Arrays.sort(old, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < old.length - 4; i++) old[i].delete();
        return finalFile;
    }

    private static void verify(File file, int expected) throws IOException {
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            while (zip.getNextEntry() != null) {
                while (zip.read(buffer) != -1) { /* ZipInputStream проверяет CRC при чтении до конца. */ }
                entries++;
            }
        }
        if (entries != expected) throw new IOException("Incomplete diagnostics ZIP");
    }

    private static void transfer(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int bytes;
        while ((bytes = input.read(buffer)) != -1) output.write(buffer, 0, bytes);
    }

    private static void line(ZipOutputStream zip, String text) throws IOException {
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.write('\n');
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(ROOT + name));
        line(zip, text);
        zip.closeEntry();
    }
}
