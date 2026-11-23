package com.example.fitcounter;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

final class HistoryView {
    private static final Locale RUSSIAN = new Locale("ru", "RU");
    private final Context context;
    private final TextView monthLabel, dateLabel, dayHistory, weekStats, monthStats;
    private final TextView[][] totals;
    private final LinearLayout calendarGrid;
    private final WorkoutRepository repository;
    private Calendar month, selected;
    private int calendarRequest, statsRequest;

    HistoryView(MainActivity activity, WorkoutRepository repository, Bundle state) {
        this.context = activity;
        this.repository = repository;
        monthLabel = activity.findViewById(R.id.month_label);
        dateLabel = activity.findViewById(R.id.selected_date);
        dayHistory = activity.findViewById(R.id.day_history);
        weekStats = activity.findViewById(R.id.week_stats);
        monthStats = activity.findViewById(R.id.month_stats);
        TextView version = activity.findViewById(R.id.stats_version);
        version.setText(activity.getString(R.string.stats_version, BuildConfig.VERSION_NAME));
        totals = new TextView[][]{
                {activity.findViewById(R.id.week_push), activity.findViewById(R.id.week_squat)},
                {activity.findViewById(R.id.month_push), activity.findViewById(R.id.month_squat)}
        };
        calendarGrid = activity.findViewById(R.id.calendar_grid);
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        month = DateRanges.month(DateRanges.day(state == null ? now : state.getLong("month", now), zone));
        selected = DateRanges.day(state == null ? now : state.getLong("selected", now), zone);
        activity.findViewById(R.id.previous_month).setOnClickListener(v -> changeMonth(-1));
        activity.findViewById(R.id.next_month).setOnClickListener(v -> changeMonth(1));
    }

    private void changeMonth(int shift) {
        month.add(Calendar.MONTH, shift);
        selected = (Calendar) month.clone();
        showCalendar();
    }

    void showCalendar() {
        int request = ++calendarRequest;
        long monthStart = month.getTimeInMillis();
        long monthEnd = DateRanges.next(month, Calendar.MONTH, 1);
        long dayStart = selected.getTimeInMillis();
        long dayEnd = DateRanges.next(selected, Calendar.DAY_OF_MONTH, 1);
        TimeZone zone = month.getTimeZone();
        String monthName = format("LLLL yyyy", month);
        monthLabel.setText(monthName.substring(0, 1).toUpperCase(RUSSIAN) + monthName.substring(1));
        dateLabel.setText(format("d MMMM yyyy", selected));
        dayHistory.setText(R.string.loading_history);
        drawCalendar(Collections.emptySet());
        repository.submit(() -> new CalendarData(repository.trainingDays(monthStart, monthEnd, zone),
                repository.history(dayStart, dayEnd)), (data, success) -> {
            if (calendarRequest != request) return;
            if (!success) {
                dayHistory.setText(R.string.history_error);
                return;
            }
            drawCalendar(data.days);
            drawHistory(data.records);
        });
    }

    private void drawCalendar(Set<Integer> marked) {
        calendarGrid.removeAllViews();
        String[] weekdays = context.getResources().getStringArray(R.array.weekdays);
        LinearLayout heading = row();
        for (String title : weekdays) {
            TextView label = new TextView(context);
            label.setGravity(Gravity.CENTER);
            label.setText(title);
            label.setTextColor(context.getResources().getColor(R.color.ui_muted));
            heading.addView(label, cellParams());
        }
        int offset = (month.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7;
        int count = month.getActualMaximum(Calendar.DAY_OF_MONTH);
        int selectedDay = selected.get(Calendar.DAY_OF_MONTH);
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        for (int i = 0; i < offset + count; i += 7) {
            LinearLayout row = row();
            for (int col = 0; col < 7; col++) {
                int day = i + col - offset + 1;
                if (day < 1 || day > count) {
                    row.addView(new View(context), cellParams());
                    continue;
                }
                Button button = new Button(context);
                boolean trained = marked.contains(day);
                button.setMinWidth(0);
                button.setMinimumWidth(0);
                button.setMinimumHeight(0);
                button.setPadding(0, 0, 0, 0);
                button.setTextSize(14);
                String dayText = numbers.format(day);
                button.setText(trained ? context.getString(R.string.calendar_marked, dayText) : dayText);
                boolean chosen = day == selectedDay;
                button.setBackgroundResource(R.drawable.ui_day);
                button.setSelected(chosen);
                button.setActivated(trained);
                button.setTextColor(context.getResources().getColor(
                        chosen ? android.R.color.white : R.color.ui_graphite));
                button.setContentDescription(trained
                        ? context.getString(R.string.trained_day, day) : dayText);
                button.setOnClickListener(v -> {
                    selected.set(Calendar.DAY_OF_MONTH, day);
                    selected = DateRanges.day(selected.getTimeInMillis(), selected.getTimeZone());
                    showCalendar();
                });
                row.addView(button, cellParams());
            }
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        calendarGrid.addView(row);
        return row;
    }

    private LinearLayout.LayoutParams cellParams() {
        return new LinearLayout.LayoutParams(0, Math.round(48 * context.getResources()
                .getDisplayMetrics().density), 1);
    }

    private void drawHistory(List<WorkoutRecord> records) {
        if (records.isEmpty()) {
            dayHistory.setText(R.string.empty_day);
            return;
        }
        DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT, RUSSIAN);
        time.setTimeZone(selected.getTimeZone());
        StringBuilder text = new StringBuilder();
        for (WorkoutRecord entry : records) {
            if (text.length() > 0) text.append('\n');
            text.append(context.getString(R.string.history_entry, time.format(entry.finishedAt),
                    context.getString(entry.exercise.label), entry.repetitions));
        }
        dayHistory.setText(text);
    }

    void showStatistics() {
        int request = ++statsRequest;
        Calendar today = DateRanges.day(System.currentTimeMillis(), TimeZone.getDefault());
        Calendar week = DateRanges.week(today), monthStart = DateRanges.month(today);
        showStatisticsStatus(R.string.loading_history);
        repository.submit(() -> new long[][]{
                repository.totals(week.getTimeInMillis(), DateRanges.next(week, Calendar.DAY_OF_MONTH, 7)),
                repository.totals(monthStart.getTimeInMillis(), DateRanges.next(monthStart, Calendar.MONTH, 1))
        }, (data, success) -> {
            if (statsRequest != request) return;
            if (!success) {
                showStatisticsStatus(R.string.history_error);
                return;
            }
            showStatisticsStatus(0);
            for (int period = 0; period < totals.length; period++) {
                for (int exercise = 0; exercise < totals[period].length; exercise++) {
                    totals[period][exercise].setText(context.getString(R.string.metric_number, data[period][exercise]));
                }
            }
        });
    }

    private void showStatisticsStatus(int message) {
        for (TextView status : new TextView[]{weekStats, monthStats}) {
            status.setVisibility(message == 0 ? View.GONE : View.VISIBLE);
            if (message != 0) status.setText(message);
        }
        if (message != 0) {
            for (TextView[] period : totals) {
                for (TextView total : period) total.setText(R.string.metric_empty);
            }
        }
    }

    void invalidateRequests() {
        calendarRequest++;
        statsRequest++;
    }

    Bundle state() {
        Bundle state = new Bundle();
        state.putLong("month", month.getTimeInMillis());
        state.putLong("selected", selected.getTimeInMillis());
        return state;
    }

    private static String format(String pattern, Calendar date) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, RUSSIAN);
        formatter.setTimeZone(date.getTimeZone());
        return formatter.format(date.getTime());
    }

    private static final class CalendarData {
        final Set<Integer> days;
        final List<WorkoutRecord> records;

        CalendarData(Set<Integer> days, List<WorkoutRecord> records) {
            this.days = days;
            this.records = records;
        }
    }
}
