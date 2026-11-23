package com.example.fitcounter;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class DeveloperPanel {
    private static final String[] SECTIONS = {"Обзор", "Устройство", "Камера", "Поза",
            "Упражнение", "Производительность", "Журнал", "Экспорт"};
    private static final int ACTIVE = 0xffa1ecc3, INK = 0xffeef4ee, MUTED = 0xffaabeb2;
    private static final int SURFACE = 0xff121f1a, CARD = 0xff1e3028, OUTLINE = 0xff40564c;
    private static final int SELECTED = 0xff2c5140, PRESSED = 0xff355a48, WARNING = 0xffffb4a2;
    private static final int PRIMARY_INK = 0xff142d21;
    private final MainActivity activity;
    private final Diagnostics diagnostics;
    private final Dialog dialog;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final TextView cameraStatus, trackingStatus;
    private final Button[] tabs = new Button[SECTIONS.length];
    private final Runnable ticker = this::tick;
    private TextView values, history;
    private Spinner sessions;
    private int section, historyRequest;
    private boolean busy, loadingHistory;
    private String selected;
    private long nextHistory;
    private View trend;
    private TextView[] overview;

    DeveloperPanel(MainActivity activity, Diagnostics diagnostics) {
        this.activity = activity;
        this.diagnostics = diagnostics;
        selected = diagnostics.id;
        boolean wide = activity.getResources().getConfiguration().screenWidthDp >= 700;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root = column();
        root.setBackground(shape(SURFACE, 28, OUTLINE));
        root.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Диагностика FitCounter", 23, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button("×", v -> dialog.dismiss());
        close.setTextSize(24);
        close.setPadding(0, 0, 0, 0);
        close.setContentDescription("Закрыть");
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);
        LinearLayout statuses = new LinearLayout(activity);
        cameraStatus = badge();
        trackingStatus = badge();
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(wide ? -2 : 0, -2, wide ? 0 : 1);
        first.setMarginEnd(dp(8));
        statuses.addView(cameraStatus, first);
        statuses.addView(trackingStatus, new LinearLayout.LayoutParams(wide ? -2 : 0, -2, wide ? 0 : 1));
        add(root, statuses, 4);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyParams.topMargin = dp(14);
        root.addView(body, bodyParams);
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < SECTIONS.length; i++) {
            int index = i;
            tabs[i] = button(SECTIONS[i], v -> select(index));
            tabs[i].setBackground(interactive(SURFACE, 14, Color.TRANSPARENT));
            tabs[i].setGravity(wide ? Gravity.CENTER_VERTICAL | Gravity.START : Gravity.CENTER);
            LinearLayout.LayoutParams tab = new LinearLayout.LayoutParams(wide ? -1 : -2, -2);
            if (wide) tab.bottomMargin = dp(3);
            else tab.setMarginEnd(dp(4));
            bar.addView(tabs[i], tab);
        }
        if (wide) {
            ScrollView rail = new ScrollView(activity);
            rail.setVerticalScrollBarEnabled(false);
            rail.addView(bar);
            LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(dp(194), -1);
            railParams.setMarginEnd(dp(18));
            body.addView(rail, railParams);
        } else {
            HorizontalScrollView nav = new HorizontalScrollView(activity);
            nav.setHorizontalScrollBarEnabled(false);
            nav.addView(bar);
            body.addView(nav);
        }

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = column();
        content.setPadding(0, dp(wide ? 2 : 10), dp(2), dp(6));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        body.addView(scroll, wide ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> diagnostics.ui.removeCallbacks(ticker));
        select(0);
    }

    void show() {
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setDimAmount(.55f);
        int width = activity.getWindow().getDecorView().getWidth();
        int height = activity.getWindow().getDecorView().getHeight();
        dialog.getWindow().setLayout(Math.min(dp(940), width - dp(20)), height * 93 / 100);
        diagnostics.ui.removeCallbacks(ticker);
        tick();
    }

    boolean showing() { return dialog.isShowing(); }
    void close() { dialog.dismiss(); }

    private void select(int index) {
        section = index;
        historyRequest++;
        loadingHistory = false;
        nextHistory = 0;
        history = null;
        sessions = null;
        trend = null;
        overview = null;
        values = null;
        content.removeAllViews();
        scroll.scrollTo(0, 0);
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == index ? ACTIVE : MUTED);
            tabs[i].setSelected(i == index);
        }
        heading(SECTIONS[index], 22, 0);
        if (index == 0) {
            overview = new TextView[3];
            LinearLayout metrics = new LinearLayout(activity);
            String[] labels = {"Повторения", "Модель · FPS", "Ошибки"};
            for (int i = 0; i < labels.length; i++) {
                LinearLayout card = card();
                overview[i] = text("—", 24, ACTIVE);
                overview[i].setTypeface(Typeface.MONOSPACE);
                card.addView(overview[i]);
                card.addView(text(labels[i], 13, MUTED));
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
                if (i != 0) cell.setMarginStart(dp(6));
                metrics.addView(card, cell);
            }
            add(content, metrics, 12);
            add(content, primary("Включить предпросмотр тренировки", v -> activity.monitorTraining()), 12);
            heading("Сессия", 17, 20);
        }
        if (index == 6) {
            LinearLayout controls = card();
            Switch detail = new Switch(activity);
            detail.setText("Подробная диагностика");
            detail.setTextColor(INK);
            detail.setMinHeight(dp(48));
            detail.setChecked(diagnostics.detailed);
            detail.setOnCheckedChangeListener((v, checked) -> {
                diagnostics.detailed = checked;
                diagnostics.log("INFO", "APP", "DETAILED_DIAGNOSTICS", checked ? "Включена" : "Выключена");
            });
            controls.addView(detail);
            sessions = new Spinner(activity);
            sessions.setContentDescription("Диагностическая сессия");
            controls.addView(sessions, new LinearLayout.LayoutParams(-1, dp(48)));
            add(controls, button("Обновить журнал", v -> { nextHistory = 0; refreshHistory(); }), 8);
            Button clear = button("Очистить диагностику", v -> confirmClear());
            clear.setTextColor(WARNING);
            add(controls, clear, 8);
            add(content, controls, 12);
        }
        if (index == 7) {
            LinearLayout export = card();
            export.addView(text("Диагностический архив · ZIP", 16, INK));
            add(export, text("Сведения об устройстве, история сессий, события упражнений, ошибки и метрики. "
                    + "При подробной диагностике архив также включает координаты суставов.", 14, MUTED), 8);
            add(export, primary("Экспортировать отчёт", v -> export((Button) v)), 14);
            add(content, export, 12);
        }
        if (index == 5) {
            LinearLayout chart = card();
            chart.addView(text("Последние 60 секунд", 16, INK));
            trend = new Trends();
            chart.addView(trend, new LinearLayout.LayoutParams(-1, dp(200)));
            add(content, chart, 12);
        }
        if (index != 6 && index != 7) values = readout();
        if (index == 4 || index == 6) {
            heading(index == 4 ? "Последние попытки" : "Последние события", 17, 20);
            history = readout();
        }
        update();
    }

    private void tick() {
        if (!showing()) return;
        if (dialog.getWindow().getDecorView().hasWindowFocus()) update();
        diagnostics.ui.postDelayed(ticker, 500);
    }

    private void update() {
        JSONObject workout = diagnostics.exerciseInfo();
        JSONObject camera = diagnostics.cameraInfo();
        String cameraState = camera.optString("state", "STOPPED");
        switch (cameraState) {
            case "ACTIVE": badge(cameraStatus, "Камера включена", ACTIVE); break;
            case "ERROR": badge(cameraStatus, "Ошибка камеры", WARNING); break;
            case "OPENING": badge(cameraStatus, "Запуск камеры", MUTED); break;
            default: badge(cameraStatus, "Камера выключена", MUTED);
        }
        switch (workout.optString("tracking_detail", workout.optString("tracking"))) {
            case "TRACKING": badge(trackingStatus, "Поза найдена", ACTIVE); break;
            case "PARTIALLY_VISIBLE": badge(trackingStatus, "Поза частично видна", WARNING); break;
            case "STALE": case "TEMPORARILY_LOST": badge(trackingStatus, "Поза потеряна", WARNING); break;
            case "NO_PERSON": badge(trackingStatus, "Человек вне кадра", MUTED); break;
            default: badge(trackingStatus, "Ожидание позы", MUTED);
        }
        JSONObject performance = section == 0 || section == 5 ? diagnostics.performance() : null;
        if (overview != null) {
            updateText(overview[0], workout.optString("repetitions", "—"));
            updateText(overview[1], !"ACTIVE".equals(cameraState) || performance.isNull("model_fps") ? "—"
                    : String.format(Locale.getDefault(), "%.1f", performance.optDouble("model_fps")));
            updateText(overview[2], Integer.toString(diagnostics.errors()));
        }
        if (values != null && !values.hasSelection()) {
            String value = "";
            switch (section) {
                case 0:
                    value = overviewDetails(workout);
                    break;
                case 1: value = lines(diagnostics.deviceInfo(), "") + "\n" + lines(diagnostics.appInfo(), ""); break;
                case 2: value = lines(diagnostics.cameraMetrics(), ""); break;
                case 3: value = pose(); break;
                case 4:
                    value = lines(workout, "");
                    if (!"ACTIVE".equals(cameraState) && diagnostics.lastMeasurement().length() != 0) {
                        value += "\nПоследнее измерение камеры\n" + lines(diagnostics.lastMeasurement(), "");
                    }
                    break;
                case 5: value = lines(performance, ""); break;
                default: break;
            }
            if (!value.contentEquals(values.getText())) values.setText(styled(value, "  "));
        }
        if (trend != null) trend.invalidate();
        if (section == 4 || section == 6) refreshHistory();
    }

    private String overviewDetails(JSONObject workout) {
        String exercise = workout.optString("exercise");
        String exerciseLabel = "SQUAT".equals(exercise) ? "Приседания"
                : "PUSH_UP".equals(exercise) ? "Отжимания" : "—";
        long minutes = (SystemClock.uptimeMillis() - diagnostics.startedUptime) / 60_000;
        String error = diagnostics.lastError();
        return "Упражнение  " + exerciseLabel
                + "\nПодход  " + (workout.optBoolean("workout_active") ? "Активен" : "Ожидание")
                + "\nЧеловек в кадре  " + (diagnostics.person() ? "Да" : "Нет")
                + "\nПоследняя поза  " + time(diagnostics.lastPerson())
                + "\nПоследняя ошибка  " + (error.isEmpty() ? "—" : error)
                + "\n\nЗапуск  " + date(diagnostics.started)
                + "\nДлительность  " + minutes + " мин"
                + "\nВерсия  " + BuildConfig.VERSION_NAME + " · сборка " + BuildConfig.VERSION_CODE
                + "\nID сессии  " + diagnostics.id;
    }

    private String pose() {
        JSONObject info = diagnostics.poseInfo();
        JSONArray joints = info.optJSONArray("joints");
        info.remove("joints");
        Diagnostics.put(info, "loss_ms", diagnostics.exerciseInfo().opt("loss_ms"));
        Diagnostics.put(info, "frames_without_confirmed_pose", diagnostics.metrics.snapshot().rejectedPoses);
        StringBuilder out = new StringBuilder(lines(info, ""));
        if (joints != null) {
            out.append("\nX / Y · confidence · достоверность · последнее измерение\n");
            for (int i = 0; i < joints.length(); i++) {
                JSONObject joint = joints.optJSONObject(i);
                out.append('\n').append(joint.optString("joint")).append('\n')
                        .append(value(joint, "x")).append(" / ").append(value(joint, "y")).append(" · ")
                        .append(value(joint, "confidence")).append(" · ")
                        .append(joint.optBoolean("valid") ? "OK" : "—").append(" · ")
                        .append(time(joint.optLong("last_valid_uptime_ms"))).append('\n');
            }
        }
        return out.toString();
    }

    private void refreshHistory() {
        if (loadingHistory || SystemClock.uptimeMillis() < nextHistory) return;
        loadingHistory = true;
        int request = ++historyRequest;
        diagnostics.history(section == 4 ? diagnostics.id : selected, section == 4, (ids, current, lines) -> {
            if (request != historyRequest || !showing()) return;
            loadingHistory = false;
            nextHistory = SystemClock.uptimeMillis() + 2000;
            if (history != null && !history.hasSelection()) {
                String historyText = lines.isEmpty() ? "Нет событий" : lines;
                if (!historyText.contentEquals(history.getText())) history.setText(styled(historyText, " · "));
            }
            if (sessions == null) return;
            selected = current;
            if (java.util.Arrays.equals(ids, (String[]) sessions.getTag())) return;
            sessions.setTag(ids);
            String[] labels = new String[ids.length];
            int index = 0;
            for (int i = 0; i < ids.length; i++) {
                labels[i] = i == 0 ? "Текущая сессия" : date(Long.parseLong(ids[i].split("-", 2)[0]));
                if (ids[i].equals(current)) index = i;
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
                @Override public View getView(int position, View convert, ViewGroup parent) {
                    TextView view = (TextView) super.getView(position, convert, parent);
                    view.setTextColor(INK);
                    return view;
                }
            };
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sessions.setOnItemSelectedListener(null);
            sessions.setAdapter(adapter);
            sessions.setSelection(index);
            sessions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                    if (ids[i].equals(selected)) return;
                    selected = ids[i];
                    nextHistory = 0;
                    loadingHistory = false;
                    refreshHistory();
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity).setTitle("Очистить диагностику?")
                .setMessage("Локальный журнал и временные ZIP-отчёты будут удалены.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Очистить", (d, w) -> diagnostics.clear(() -> {
                    nextHistory = 0;
                    refreshHistory();
                    notifyUser("DIAGNOSTICS_CLEAR_FAILED".equals(diagnostics.lastError())
                            ? "Не удалось удалить часть данных" : "Диагностика очищена");
                })).show();
    }

    private void export(Button trigger) {
        if (busy) return;
        busy = true;
        trigger.setEnabled(false);
        DiagnosticsExport.create(diagnostics, file -> {
            busy = false;
            trigger.setEnabled(true);
            if (!showing()) return;
            if (file == null) {
                notifyUser("Не удалось сформировать отчёт");
                return;
            }
            new AlertDialog.Builder(activity).setTitle("ZIP готов")
                    .setMessage(file.getName())
                    .setNeutralButton(android.R.string.cancel, null)
                    .setNegativeButton("Сохранить", (d, w) -> activity.saveDiagnostics(file))
                    .setPositiveButton("Поделиться", (d, w) -> activity.shareDiagnostics(file)).show();
        });
    }

    private TextView readout() {
        LinearLayout surface = card();
        TextView value = text("", 13, INK);
        value.setTypeface(Typeface.MONOSPACE);
        value.setTextIsSelectable(true);
        value.setLineSpacing(dp(4), 1);
        surface.addView(value);
        add(content, surface, 10);
        return value;
    }

    private LinearLayout card() {
        LinearLayout result = column();
        result.setBackground(shape(CARD, 18, Color.TRANSPARENT));
        result.setPadding(dp(12), dp(12), dp(12), dp(12));
        return result;
    }

    private void heading(String label, int size, int margin) {
        TextView title = text(label, size, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add(content, title, margin);
    }

    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private TextView badge() {
        TextView badge = text("", 13, MUTED);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        return badge;
    }

    private void badge(TextView badge, String label, int color) {
        if (label.contentEquals(badge.getText())) return;
        badge.setBackground(shape(color == ACTIVE ? SELECTED : CARD, 20, Color.TRANSPARENT));
        badge.setTextColor(color);
        badge.setText(label);
    }

    private static void updateText(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private SpannableString styled(String input, String separator) {
        SpannableString result = new SpannableString(input);
        int start = 0;
        while (start < input.length()) {
            int end = input.indexOf('\n', start);
            if (end < 0) end = input.length();
            int label = input.indexOf(separator, start);
            if (label > start && label < end) {
                result.setSpan(new ForegroundColorSpan(MUTED), start, label, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (end > start && Character.isLetterOrDigit(input.charAt(start))) {
                result.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end + 1;
        }
        return result;
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private Button button(String title, View.OnClickListener click) {
        Button button = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(INK);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(interactive(SURFACE, 24, OUTLINE));
        button.setOnClickListener(click);
        return button;
    }

    private Button primary(String title, View.OnClickListener click) {
        Button button = button(title, click);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shape(CARD, 24, OUTLINE));
        states.addState(new int[]{android.R.attr.state_pressed}, shape(0xff81cfa5, 24, Color.TRANSPARENT));
        states.addState(new int[]{android.R.attr.state_focused}, shape(ACTIVE, 24, INK));
        states.addState(new int[0], shape(ACTIVE, 24, Color.TRANSPARENT));
        button.setBackground(states);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{MUTED, PRIMARY_INK}));
        return button;
    }

    private StateListDrawable interactive(int normal, int radius, int stroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(PRESSED, radius, stroke));
        states.addState(new int[]{android.R.attr.state_focused}, shape(SELECTED, radius, ACTIVE));
        states.addState(new int[]{android.R.attr.state_selected}, shape(SELECTED, radius, Color.TRANSPARENT));
        states.addState(new int[0], shape(normal, radius, stroke));
        return states;
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView text(String text, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int dp) { return Math.round(dp * activity.getResources().getDisplayMetrics().density); }
    private void notifyUser(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }
    static String date(long epoch) {
        return new SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.getDefault()).format(new Date(epoch));
    }
    private static String time(long uptime) {
        return uptime <= 0 ? "Недоступно" : date(System.currentTimeMillis() - SystemClock.uptimeMillis() + uptime);
    }
    private static String value(JSONObject object, String key) {
        String text = object.optString(key);
        return object.isNull(key) || text.isEmpty() ? "Недоступно" : text;
    }
    private static String lines(JSONObject data, String prefix) {
        StringBuilder text = new StringBuilder();
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject child = data.optJSONObject(key);
            if (child == null) text.append(prefix).append(key).append("  ").append(value(data, key)).append('\n');
            else text.append(prefix).append(key).append('\n').append(lines(child, prefix + "  "));
        }
        return text.toString();
    }

    private final class Trends extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String[] labels = {"Модель, FPS", "Inference p50, мс", "Возраст, мс", "PSS, МиБ"};

        Trends() {
            super(activity);
            setContentDescription("Графики FPS, inference, возраста результата и памяти за 60 секунд");
        }

        @Override protected void onDraw(Canvas canvas) {
            Diagnostics.Trend[] samples = diagnostics.trends();
            long now = SystemClock.uptimeMillis();
            float row = getHeight() / (float) labels.length;
            paint.setTextSize(dp(12));
            paint.setStrokeWidth(dp(1));
            for (int metric = 0; metric < labels.length; metric++) {
                double max = 0;
                for (Diagnostics.Trend sample : samples) {
                    if (sample.time >= now - 60_000) max = Math.max(max, sample.values[metric]);
                }
                float top = row * metric;
                float baseline = top + row - dp(4);
                paint.setColor(MUTED);
                canvas.drawText(labels[metric] + (max > 0 ? " · макс. " + Math.round(max) : ""),
                        0, top + dp(13), paint);
                canvas.drawLine(0, baseline, getWidth(), baseline, paint);
                if (max <= 0) continue;
                paint.setColor(ACTIVE);
                float previousX = 0, previousY = 0;
                long previousTime = 0;
                for (Diagnostics.Trend sample : samples) {
                    if (sample.time < now - 60_000 || sample.values[metric] < 0) continue;
                    float x = getWidth() * (1 - (now - sample.time) / 60_000f);
                    float y = baseline - (float) (sample.values[metric] / max) * (row - dp(23));
                    if (sample.time - previousTime < 2000) canvas.drawLine(previousX, previousY, x, y, paint);
                    else canvas.drawCircle(x, y, dp(1), paint);
                    previousX = x;
                    previousY = y;
                    previousTime = sample.time;
                }
            }
        }
    }
}
