package com.example.fitcounter;

import android.os.Bundle;
import java.util.UUID;

final class WorkoutSession {
    private Exercise exercise;
    private ExerciseCounter counter;
    private String id;
    private long finishedAt;
    private int repetitions, side = -1;
    private int straight;
    private double standingHeight;
    private long startedAt;
    private double angle = Double.NaN;

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
        this.counter = new ExerciseCounter(straight, depth);
        abandonCycle();
    }

    boolean running() { return id != null && finishedAt == 0; }
    boolean pending() { return id != null && finishedAt != 0; }
    int repetitions() { return repetitions; }
    double angle() { return angle; }
    ExerciseCounter.Phase phase() { return counter.phase(); }

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
        counter.reset();
        angle = Double.NaN;
        side = -1;
        standingHeight = 0;
    }

    void accept(PoseReading reading) {
        PoseReading.Limb[] limbs = reading.limbs[exercise.ordinal()];
        int bestSide = limbs[0] == null || limbs[1] != null
                && limbs[1].confidence > limbs[0].confidence ? 1 : 0;
        if (!running() || reading.time < startedAt) {
            angle = limbs[bestSide] == null ? Double.NaN : limbs[bestSide].angle;
            return;
        }
        if (side < 0) side = bestSide;
        PoseReading.Limb limb = limbs[side];
        if (limb == null) {
            abandonCycle();
            return;
        }

        if (limb.startPosition && limb.angle >= straight
                && (counter.phase() == ExerciseCounter.Phase.FIND_START
                || counter.phase() == ExerciseCounter.Phase.READY)) {
            standingHeight = limb.hipHeight;
        }
        angle = limb.angle;
        boolean bottom = exercise == Exercise.PUSH_UP
                || standingHeight - limb.hipHeight >= 0.18 * limb.legLength;
        if (counter.accept(reading.time, angle, limb.startPosition, bottom)) {
            repetitions++;
        }
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
