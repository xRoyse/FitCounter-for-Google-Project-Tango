package com.example.fitcounter;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.text.NumberFormat;

public class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 1;
    private static final long POSE_TIMEOUT_MS = 800;
    private static final int[] TAB_IDS = {R.id.tab_training, R.id.tab_calendar, R.id.tab_stats};
    private static final int[] SCREEN_IDS = {R.id.training_screen, R.id.calendar_screen, R.id.stats_screen};
    private static final int[] PHASE_LABELS = {R.string.find_start, R.string.ready, R.string.lowering,
            R.string.bottom, R.string.rising};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable poseExpired = this::expirePose;
    private final Runnable overlayExpired = () -> this.skeleton.show(null, null, false);
    private final Runnable openCamera = this::openPreview;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (pipeline != null && displayDegrees() != rotation) {
                stopPreview();
                maybeStartPreview();
            }
        }
    };

    private WorkoutRepository repository;
    private WorkoutSession session;
    private HistoryView history;
    private SharedPreferences settings;
    private Exercise exercise;
    private CameraFrameSource source;
    private PosePipeline pipeline;
    private DisplayManager displayManager;
    private TextureView texture;
    private PreviewPane preview;
    private SkeletonView skeleton;
    private Spinner selector;
    private TextView repetitions, angle, angleLabel, phase, hint, cameraFacingLabel, cameraStatus, saveMessage;
    private Button start, finish, reset, retry, thresholds;
    private View switchCamera;
    private FrameSource.Facing cameraFacing;
    private boolean canSwitchCamera;
    private int tab, generation, rotation, cameraError;
    private boolean resumed, destroyed, permissionAsked, cameraReady, modelReady, mirror, saving;
    private long lastPoseFrame, previewWindow;
    private int previewFrames;
    private int workoutMessage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= 35) EdgeToEdge.apply(findViewById(R.id.main));

        settings = getSharedPreferences("training", MODE_PRIVATE);
        exercise = Exercise.values()[state == null ? settings.getInt("exercise", 0)
                : state.getInt("exercise", 0)];
        permissionAsked = state != null && state.getBoolean("permissionAsked");
        cameraFacing = state == null ? FrameSource.Facing.AUTO
                : FrameSource.Facing.valueOf(state.getString("cameraFacing", FrameSource.Facing.AUTO.name()));
        repository = WorkoutRepository.shared(this);
        session = new WorkoutSession(exercise, upper(), lower(),
                state == null ? null : state.getBundle("session"));
        history = new HistoryView(this, repository, state == null ? null : state.getBundle("history"));
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);

        preview = findViewById(R.id.preview);
        skeleton = findViewById(R.id.skeleton);
        texture = findViewById(R.id.camera_texture);
        repetitions = findViewById(R.id.repetitions);
        angle = findViewById(R.id.angle);
        angleLabel = findViewById(R.id.angle_label);
        phase = findViewById(R.id.phase);
        hint = findViewById(R.id.hint);
        saveMessage = findViewById(R.id.save_message);
        cameraStatus = findViewById(R.id.camera_status);
        cameraFacingLabel = findViewById(R.id.camera_facing);
        start = findViewById(R.id.start_workout);
        finish = findViewById(R.id.finish_workout);
        reset = findViewById(R.id.reset_workout);
        retry = findViewById(R.id.retry_camera);
        switchCamera = findViewById(R.id.switch_camera);
        thresholds = findViewById(R.id.thresholds);
        selector = findViewById(R.id.exercise);

        for (int i = 0; i < TAB_IDS.length; i++) {
            final int index = i;
            findViewById(TAB_IDS[i]).setOnClickListener(v -> showTab(index));
        }
        configureSelector();
        configurePreview();
        start.setOnClickListener(v -> {
            workoutMessage = 0;
            session.start(SystemClock.uptimeMillis());
            render();
        });
        reset.setOnClickListener(v -> {
            workoutMessage = 0;
            session.reset(SystemClock.uptimeMillis());
            render();
        });
        finish.setOnClickListener(v -> saveWorkout());
        retry.setOnClickListener(v -> {
            if (!hasPermission()) {
                askCameraPermission();
                return;
            }
            stopPreview();
            maybeStartPreview();
        });
        thresholds.setOnClickListener(v -> editThresholds());
        switchCamera.setOnClickListener(v -> {
            boolean front = cameraReady ? mirror : cameraFacing == FrameSource.Facing.FRONT;
            cameraFacing = front ? FrameSource.Facing.REAR : FrameSource.Facing.FRONT;
            cameraError = 0;
            stopPreview();
            maybeStartPreview();
        });
        showTab(state == null ? 0 : state.getInt("tab"));
        if (session.pending()) saveWorkout();
    }

    @TargetApi(35)
    private static final class EdgeToEdge {
        static void apply(View root) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
    }

    private void configureSelector() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.push_up), getString(R.string.squat)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(adapter);
        selector.setSelection(exercise.ordinal());
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View item, int position, long id) {
                if (position == exercise.ordinal() || session.running() || session.pending()) return;
                exercise = Exercise.values()[position];
                settings.edit().putInt("exercise", position).apply();
                session.select(exercise, upper(), lower());
                session.reset(SystemClock.uptimeMillis());
                workoutMessage = 0;
                render();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void configurePreview() {
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                maybeStartPreview();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                stopPreview();
                if (source == null) return true;
                source.stop(surface::release);
                return false;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                if (!BuildConfig.DEBUG || !cameraReady) return;
                long now = SystemClock.uptimeMillis();
                if (previewFrames++ == 0) previewWindow = now;
                if (previewFrames == 120) {
                    android.util.Log.d("FitCounter", "UI preview fps="
                            + 119_000f / Math.max(1, now - previewWindow));
                    previewFrames = 0;
                }
            }
        });
    }

    private void showTab(int index) {
        int previous = tab;
        tab = index;
        for (int i = 0; i < TAB_IDS.length; i++) {
            View navigation = findViewById(TAB_IDS[i]);
            navigation.setSelected(i == tab);
            navigation.setEnabled(i != tab);
            findViewById(SCREEN_IDS[i]).setVisibility(i == tab ? View.VISIBLE : View.GONE);
        }
        if (tab == 0) {
            maybeStartPreview();
        } else {
            if (previous == 0) stopPreview();
            refreshHistory();
        }
        render();
    }

    private void refreshHistory() {
        if (tab == 1) history.showCalendar();
        if (tab == 2) history.showStatistics();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        displayManager.registerDisplayListener(displayListener, ui);
        if (tab == 0) maybeStartPreview();
        else refreshHistory();
    }

    @Override protected void onPause() {
        resumed = false;
        displayManager.unregisterDisplayListener(displayListener);
        stopPreview();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        history.invalidateRequests();
        stopPreview();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", tab);
        state.putInt("exercise", exercise.ordinal());
        state.putBoolean("permissionAsked", permissionAsked);
        state.putString("cameraFacing", cameraFacing.name());
        state.putBundle("session", session.state());
        state.putBundle("history", history.state());
        super.onSaveInstanceState(state);
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void askCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !hasPermission()) {
            permissionAsked = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == CAMERA_REQUEST) maybeStartPreview();
    }

    private void maybeStartPreview() {
        if (!resumed || destroyed || tab != 0 || pipeline != null) return;
        // При повороте старое окно может ещё завершать работу с поверхностью.
        ui.removeCallbacks(openCamera);
        ui.postDelayed(openCamera, 250);
    }

    private void openPreview() {
        if (!resumed || destroyed || tab != 0 || pipeline != null) return;
        if (!hasPermission()) {
            cameraError = R.string.camera_permission;
            render();
            if (!permissionAsked) askCameraPermission();
            return;
        }
        if (!texture.isAvailable()) return;
        cameraError = 0;
        int ticket = ++generation;
        rotation = displayDegrees();
        PosePipeline current = new PosePipeline(this, new PosePipeline.Listener() {
            @Override public void onReady() {
                if (ticket != generation) return;
                modelReady = true;
                if (cameraReady) ui.postDelayed(poseExpired, POSE_TIMEOUT_MS);
                render();
            }

            @Override public void onPose(PoseReading reading, Bitmap image) {
                if (ticket != generation || cameraError != 0 || !cameraReady
                        || reading.frameId <= lastPoseFrame
                        || SystemClock.uptimeMillis() - reading.time > PosePipeline.MAX_DISPLAY_AGE_MS) return;
                lastPoseFrame = reading.frameId;
                skeleton.show(image == null ? null : reading, image, mirror);
                ui.removeCallbacks(overlayExpired);
                if (image != null) ui.postAtTime(overlayExpired, reading.time + PosePipeline.MAX_DISPLAY_AGE_MS);
                session.accept(reading);
                ui.removeCallbacks(poseExpired);
                ui.postAtTime(poseExpired, reading.time + POSE_TIMEOUT_MS);
                render();
            }

            @Override public void onModelError() {
                if (ticket != generation) return;
                cameraError = R.string.model_error;
                expirePose();
            }
        });
        pipeline = current;
        if (source == null) source = new CameraFrameSource();
        source.start(texture.getSurfaceTexture(), rotation, cameraFacing, new FrameSource.Listener() {
            @Override public void onCameraOptions(boolean canSwitch) {
                ui.post(() -> {
                    if (ticket != generation) return;
                    canSwitchCamera = canSwitch;
                    render();
                });
            }

            @Override public void onPreview(int width, int height, boolean mirrored) {
                ui.post(() -> {
                    if (ticket != generation) return;
                    preview.setFrameSize(width, height);
                    mirror = mirrored;
                    cameraReady = true;
                    if (modelReady) ui.postDelayed(poseExpired, POSE_TIMEOUT_MS);
                    render();
                });
            }

            @Override public void onFrame(Frame frame) { current.offer(frame); }

            @Override public void onCameraError() {
                ui.post(() -> {
                    if (ticket != generation) return;
                    cameraReady = false;
                    cameraError = R.string.camera_error;
                    expirePose();
                });
            }
        });
        render();
    }

    private void expirePose() {
        ui.removeCallbacks(poseExpired);
        ui.removeCallbacks(overlayExpired);
        skeleton.show(null, null, false);
        session.expirePose();
        render();
    }

    private void stopPreview() {
        generation++;
        ui.removeCallbacks(openCamera);
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (source != null) source.stop();
        ui.removeCallbacks(poseExpired);
        cameraReady = modelReady = false;
        previewFrames = 0;
        ui.removeCallbacks(overlayExpired);
        skeleton.show(null, null, false);
        session.resetTracking();
        render();
    }

    private int displayDegrees() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private void saveWorkout() {
        if (saving) return;
        WorkoutRecord result = session.finish(System.currentTimeMillis());
        if (result == null) {
            workoutMessage = R.string.empty_workout;
            render();
            return;
        }
        saving = true;
        workoutMessage = R.string.saving;
        render();
        repository.submit(() -> {
            repository.save(result);
            return result.id;
        }, (id, success) -> {
            if (destroyed) return;
            saving = false;
            if (success) session.saved(id);
            workoutMessage = success ? R.string.saved : R.string.save_error;
            refreshHistory();
            render();
        });
    }

    private void render() {
        boolean running = session.running();
        boolean pending = session.pending();
        boolean ready = cameraReady && modelReady && cameraError == 0;
        start.setEnabled(ready && !running && !pending);
        finish.setEnabled((running || pending) && !saving);
        finish.setText(pending && !saving ? R.string.retry_save : R.string.finish);
        reset.setEnabled(running || !pending && session.repetitions() > 0);
        thresholds.setEnabled(!running && !pending);
        selector.setEnabled(!running && !pending);
        String count = getString(R.string.metric_number, session.repetitions());
        if (!count.contentEquals(repetitions.getText())) {
            repetitions.setText(count);
            repetitions.setContentDescription(getString(R.string.completed_reps, session.repetitions()));
        }
        boolean hasAngle = !Double.isNaN(session.angle());
        String measure = hasAngle ? getString(R.string.metric_angle, Math.round(session.angle()))
                : getString(R.string.metric_empty);
        if (!measure.contentEquals(angle.getText())) {
            angle.setText(measure);
            angle.setContentDescription(hasAngle ? getString(R.string.current_angle, Math.round(session.angle()))
                    : getString(R.string.unknown_angle));
        }
        setLabel(angleLabel, exercise == Exercise.PUSH_UP ? R.string.elbow_label : R.string.knee_label);
        setLabel(phase, running ? PHASE_LABELS[session.phase().ordinal()] : pending
                ? (saving ? R.string.saving : R.string.save_error) : R.string.idle);
        setLabel(hint, exercise.hint);
        showMessage(saveMessage, pending ? 0 : workoutMessage);
        int status = cameraError != 0 ? cameraError : !ready ? R.string.camera_loading : trackingLabel();
        if (!Integer.valueOf(status).equals(cameraStatus.getTag())) {
            boolean warning = cameraError != 0 || status == R.string.no_pose || status == R.string.no_person
                    || status == R.string.pose_timeout;
            cameraStatus.setBackgroundResource(warning ? R.drawable.ui_warn : R.drawable.ui_good);
            cameraStatus.setTextColor(getResources().getColor(warning ? R.color.ui_coral : R.color.ui_emerald));
            cameraStatus.setText(status);
            cameraStatus.setTag(status);
        }
        setLabel(cameraFacingLabel, cameraReady ? (mirror ? R.string.front_camera : R.string.rear_camera)
                : R.string.preview_label);
        retry.setVisibility(cameraError != 0 ? View.VISIBLE : View.GONE);
        switchCamera.setVisibility(canSwitchCamera && hasPermission() ? View.VISIBLE : View.GONE);
        switchCamera.setEnabled(cameraReady || cameraError != 0);
    }

    private int trackingLabel() {
        switch (session.tracking()) {
            case TRACKING: return R.string.pose_detected;
            case PARTIALLY_VISIBLE: return R.string.no_pose;
            case NO_PERSON: return R.string.no_person;
            case STALE: return R.string.pose_timeout;
            default: return R.string.pose_acquiring;
        }
    }

    private void showMessage(TextView view, int message) {
        view.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
        if (message != 0) setLabel(view, message);
    }

    private void setLabel(TextView view, int text) {
        if (Integer.valueOf(text).equals(view.getTag())) return;
        view.setText(text);
        view.setTag(text);
    }

    private int upper() { return settings.getInt(exercise.name() + "_upper", exercise.straight); }

    private int lower() { return settings.getInt(exercise.name() + "_lower", exercise.depth); }

    private void editThresholds() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);
        EditText upper = addThreshold(fields, R.string.upper_threshold, upper());
        EditText lower = addThreshold(fields, R.string.lower_threshold, lower());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.thresholds)
                .setView(fields).setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.restore_defaults, (d, button) ->
                        saveThresholds(exercise.straight, exercise.depth))
                .setPositiveButton(R.string.apply, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int high = Integer.parseInt(upper.getText().toString().trim());
                int low = Integer.parseInt(lower.getText().toString().trim());
                if (!ExerciseCounter.validThresholds(high, low)) throw new IllegalArgumentException();
                saveThresholds(high, low);
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                lower.setError(getString(R.string.threshold_error));
            }
        }));
        dialog.show();
    }

    private EditText addThreshold(LinearLayout parent, int labelId, int value) {
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(NumberFormat.getIntegerInstance().format(value));
        TextView label = new TextView(this);
        label.setText(labelId);
        label.setLabelFor(input.getId());
        parent.addView(label);
        parent.addView(input);
        return input;
    }

    private void saveThresholds(int high, int low) {
        settings.edit().putInt(exercise.name() + "_upper", high)
                .putInt(exercise.name() + "_lower", low).apply();
        session.select(exercise, high, low);
        render();
    }
}
