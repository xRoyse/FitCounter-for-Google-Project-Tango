package com.example.fitcounter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WorkoutRepository extends SQLiteOpenHelper {
    interface Query<T> { T run(); }
    interface Callback<T> { void complete(T result, boolean success); }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static WorkoutRepository instance;

    static synchronized WorkoutRepository shared(Context context) {
        if (instance == null) instance = new WorkoutRepository(context.getApplicationContext(), "workouts.db");
        return instance;
    }

    WorkoutRepository(Context context, String name) {
        super(context, name, null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE workouts (id TEXT PRIMARY KEY NOT NULL, finished_at INTEGER NOT NULL,"
                + " exercise TEXT NOT NULL, repetitions INTEGER NOT NULL CHECK (repetitions > 0))");
        db.execSQL("CREATE INDEX workouts_by_date ON workouts (finished_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Missing workouts migration " + oldVersion + " to " + newVersion);
    }

    void save(WorkoutRecord record) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("id", record.id);
        values.put("finished_at", record.finishedAt);
        values.put("exercise", record.exercise.name());
        values.put("repetitions", record.repetitions);
        try {
            db.insertOrThrow("workouts", null, values);
        } catch (SQLiteConstraintException e) {
            try (Cursor existing = db.query("workouts",
                    new String[]{"finished_at", "exercise", "repetitions"}, "id = ?",
                    new String[]{record.id}, null, null, null)) {
                if (!existing.moveToFirst() || existing.getLong(0) != record.finishedAt
                        || !existing.getString(1).equals(record.exercise.name())
                        || existing.getInt(2) != record.repetitions) throw e;
            }
        }
    }

    List<WorkoutRecord> history(long start, long end) {
        List<WorkoutRecord> records = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("workouts",
                new String[]{"id", "finished_at", "exercise", "repetitions"}, range(), args(start, end),
                null, null, "finished_at DESC, id")) {
            while (c.moveToNext()) {
                records.add(new WorkoutRecord(c.getString(0), c.getLong(1),
                        Exercise.valueOf(c.getString(2)), c.getInt(3)));
            }
        }
        return records;
    }

    Set<Integer> trainingDays(long start, long end, TimeZone zone) {
        Set<Integer> days = new HashSet<>();
        Calendar date = Calendar.getInstance(zone);
        try (Cursor c = getReadableDatabase().query("workouts", new String[]{"finished_at"},
                range(), args(start, end), null, null, null)) {
            while (c.moveToNext()) {
                date.setTimeInMillis(c.getLong(0));
                days.add(date.get(Calendar.DAY_OF_MONTH));
            }
        }
        return days;
    }

    long[] totals(long start, long end) {
        long[] result = new long[Exercise.values().length];
        try (Cursor c = getReadableDatabase().query("workouts",
                new String[]{"exercise", "SUM(repetitions)"}, range(), args(start, end),
                "exercise", null, null)) {
            while (c.moveToNext()) result[Exercise.valueOf(c.getString(0)).ordinal()] = c.getLong(1);
        }
        return result;
    }

    <T> void submit(Query<T> query, Callback<T> callback) {
        Handler ui = new Handler(Looper.getMainLooper());
        IO.execute(() -> {
            T result;
            try {
                result = query.run();
            } catch (RuntimeException e) {
                Log.w("FitCounter", "Workout database operation failed", e);
                ui.post(() -> callback.complete(null, false));
                return;
            }
            ui.post(() -> callback.complete(result, true));
        });
    }

    private static String range() { return "finished_at >= ? AND finished_at < ?"; }

    private static String[] args(long start, long end) {
        return new String[]{Long.toString(start), Long.toString(end)};
    }
}
