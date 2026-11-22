package com.example.fitcounter;

import android.os.Bundle;
import java.util.UUID;

final class WorkoutSession {
    private Exercise exercise;
    private final ExerciseCounter[] counters = new ExerciseCounter[2];
    private String id;
    private long finishedAt;
    private int repetitions;
    private int straight;
    private final double[] standingHeight = new double[2];
    private long startedAt, lastFrame;
    private double angle = Double.NaN;
    private ExerciseCounter.Phase phase = ExerciseCounter.Phase.FIND_START;
    private final TrackingStatus tracking = new TrackingStatus();

    WorkoutSession(Exercise exercise, int straight, int depth, Bundle saved) {
        select(exercise, straight, depth);
        if (saved != null) {
            id = saved.getString("id");
            finishedAt = saved.getLong("finished");
            repetitions = saved.getInt("repetitions");
        }
    }

    void select(Exercise exercise, int straight, int depth) {
        this.exercise = exercise;
        this.straight = straight;
        for (int i = 0; i < counters.length; i++) counters[i] = new ExerciseCounter(straight, depth);
        abandonCycle();
        tracking.reset();
    }

    boolean running() { return id != null && finishedAt == 0; }
    boolean pending() { return id != null && finishedAt != 0; }
    int repetitions() { return repetitions; }
    double angle() { return angle; }
    ExerciseCounter.Phase phase() { return phase; }
    TrackingStatus.State tracking() { return tracking.state(); }

    void expirePose() {
        abandonCycle();
        tracking.expire();
    }

    void resetTracking() {
        abandonCycle();
        tracking.reset();
    }

    void start(long time) {
        if (id != null) return;
        id = UUID.randomUUID().toString();
        startedAt = time;
        repetitions = 0;
        abandonCycle();
    }

    void reset(long time) {
        if (pending()) return;
        repetitions = 0;
        startedAt = time;
        abandonCycle();
    }

    void abandonCycle() {
        for (ExerciseCounter counter : counters) counter.reset();
        angle = Double.NaN;
        phase = ExerciseCounter.Phase.FIND_START;
        java.util.Arrays.fill(standingHeight, 0);
    }

    void accept(PoseReading reading) {
        if (reading.frameId <= lastFrame) return;
        lastFrame = reading.frameId;
        PoseReading.Limb[] limbs = reading.limbs[exercise.ordinal()];
        int bestSide = limbs[0] == null || limbs[1] != null
                && limbs[1].confidence > limbs[0].confidence ? 1 : 0;
        angle = limbs[bestSide] == null ? Double.NaN : limbs[bestSide].angle;
        tracking.update(reading.time, reading.pose != null, !Double.isNaN(angle));
        if (!running() || reading.time < startedAt) return;

        // Каждая сторона подтверждает полный цикл своими измерениями. При зачёте
        // очищаем незавершённый цикл второй стороны, чтобы исключить двойной счёт.
        boolean left = advance(0, limbs[0], reading.time);
        boolean right = advance(1, limbs[1], reading.time);
        if (left || right) {
            repetitions++;
            if (!left) counters[0].synchronizeAfterRepetition(reading.time, atTop(limbs[0]));
            if (!right) counters[1].synchronizeAfterRepetition(reading.time, atTop(limbs[1]));
        }
        phase = counters[0].phase().ordinal() >= counters[1].phase().ordinal()
                ? counters[0].phase() : counters[1].phase();
        int other = 1 - bestSide;
        if (limbs[other] != null && counters[other].phase().ordinal() > counters[bestSide].phase().ordinal()) {
            angle = limbs[other].angle;
        }
    }

    private boolean advance(int side, PoseReading.Limb limb, long time) {
        ExerciseCounter counter = counters[side];
        if (limb == null) {
            counter.missing(time);
            return false;
        }

        if (atTop(limb)
                && (counter.phase() == ExerciseCounter.Phase.FIND_START
                || counter.phase() == ExerciseCounter.Phase.READY)) {
            standingHeight[side] = standingHeight[side] == 0 || counter.phase() == ExerciseCounter.Phase.FIND_START
                    ? limb.height : 0.7 * standingHeight[side] + 0.3 * limb.height;
        }
        boolean bottom = standingHeight[side] - limb.height >= (exercise == Exercise.PUSH_UP ? 0.12 : 0.15)
                * limb.limbLength;
        return counter.accept(time, limb.angle, limb.startPosition, bottom);
    }

    private boolean atTop(PoseReading.Limb limb) {
        return limb != null && limb.startPosition && limb.angle >= straight;
    }

    WorkoutRecord finish(long date) {
        if (running()) {
            abandonCycle();
            if (repetitions == 0) {
                id = null;
                return null;
            }
            finishedAt = date;
        }
        return pending() ? new WorkoutRecord(id, finishedAt, exercise, repetitions) : null;
    }

    void saved(String savedId) {
        if (!pending() || !id.equals(savedId)) return;
        id = null;
        finishedAt = 0;
    }

    Bundle state() {
        Bundle saved = new Bundle();
        saved.putString("id", id);
        saved.putLong("finished", finishedAt);
        saved.putInt("repetitions", repetitions);
        return saved;
    }
}
