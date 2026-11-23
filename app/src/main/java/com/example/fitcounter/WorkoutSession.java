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
    private int depth, selectedSide, rejected;
    private long phaseTime, repTime;
    private String blocking = "WORKOUT_NOT_STARTED";
    private ExerciseCounter.Reason lastRejection = ExerciseCounter.Reason.NONE, pendingRejection = lastRejection;
    private ExerciseCounter.Phase previousPhase = ExerciseCounter.Phase.FIND_START;
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
        this.depth = depth;
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
    long missingSince() { return tracking.missingSince(); }
    Exercise exercise() { return exercise; }
    ExerciseCounter.Phase previousPhase() { return previousPhase; }
    long phaseTime() { return phaseTime; }
    long repTime() { return repTime; }
    int rejected() { return rejected; }
    String rejection() { return lastRejection.name(); }
    String blocking() { return running() ? blocking : "WORKOUT_NOT_STARTED"; }
    int upper() { return straight; }
    int lower() { return depth; }
    int side() { return selectedSide; }
    String direction() { return counters[selectedSide].direction; }

    void expirePose() {
        rejectInterruptedAttempt();
        abandonCycle();
        tracking.expire();
        blocking = "NO_NEW_FRAMES";
    }

    void resetTracking() {
        rejectInterruptedAttempt();
        abandonCycle();
        tracking.reset();
    }

    void start(long time) {
        if (id != null) return;
        id = UUID.randomUUID().toString();
        startedAt = time;
        repetitions = 0;
        resetAttempts();
        abandonCycle();
    }

    void reset(long time) {
        if (pending()) return;
        repetitions = 0;
        resetAttempts();
        startedAt = time;
        abandonCycle();
    }

    void abandonCycle() {
        for (ExerciseCounter counter : counters) counter.reset();
        angle = Double.NaN;
        phase = ExerciseCounter.Phase.FIND_START;
        previousPhase = phase;
        phaseTime = 0;
        pendingRejection = ExerciseCounter.Reason.NONE;
        blocking = "WAITING_FOR_MEASUREMENT";
        java.util.Arrays.fill(standingHeight, 0);
    }

    void accept(PoseReading reading) {
        if (reading.frameId <= lastFrame) return;
        lastFrame = reading.frameId;
        PoseReading.Limb[] limbs = reading.limbs[exercise.ordinal()];
        int bestSide = limbs[0] == null && limbs[1] == null ? selectedSide
                : limbs[0] == null || limbs[1] != null && limbs[1].confidence > limbs[0].confidence ? 1 : 0;
        angle = limbs[bestSide] == null ? Double.NaN : limbs[bestSide].angle;
        tracking.update(reading.time, reading.pose != null, !Double.isNaN(angle));
        if (!running() || reading.time < startedAt) return;

        if ("BODY_JUMP".equals(reading.validation)) {
            rejectInterruptedAttempt();
            for (ExerciseCounter counter : counters) counter.reset();
            java.util.Arrays.fill(standingHeight, 0);
        }
        // Каждая сторона подтверждает полный цикл своими измерениями. При зачёте
        // очищаем незавершённый цикл второй стороны, чтобы исключить двойной счёт.
        boolean left = advance(0, limbs[0], reading.time);
        boolean right = advance(1, limbs[1], reading.time);
        if (left || right) {
            repetitions++;
            repTime = reading.time;
            pendingRejection = ExerciseCounter.Reason.NONE;
            if (!left) counters[0].synchronizeAfterRepetition(reading.time, atTop(limbs[0]));
            if (!right) counters[1].synchronizeAfterRepetition(reading.time, atTop(limbs[1]));
        } else {
            for (ExerciseCounter counter : counters) {
                if (counter.rejection != ExerciseCounter.Reason.NONE) pendingRejection = counter.rejection;
            }
            if (pendingRejection != ExerciseCounter.Reason.NONE
                    && !counters[0].attemptActive() && !counters[1].attemptActive()) {
                reject(pendingRejection);
            }
        }
        int other = 1 - bestSide;
        if (left || right) selectedSide = left && right ? bestSide : left ? 0 : 1;
        else selectedSide = limbs[other] != null
                && counters[other].phase().ordinal() > counters[bestSide].phase().ordinal() ? other : bestSide;
        ExerciseCounter selected = counters[selectedSide];
        PoseReading.Limb measurement = limbs[selectedSide];
        angle = measurement == null ? Double.NaN : measurement.angle;
        if (phase != selected.phase()) {
            previousPhase = phase;
            phase = selected.phase();
            phaseTime = reading.time;
        }
        blocking = measurement == null ? reading.limbReasons[exercise.ordinal()][selectedSide]
                : selected.reason.name();
    }

    private void resetAttempts() {
        rejected = 0;
        repTime = 0;
        lastRejection = ExerciseCounter.Reason.NONE;
    }

    private void rejectInterruptedAttempt() {
        if (counters[0].attemptedRep() || counters[1].attemptedRep()) {
            reject(ExerciseCounter.Reason.TRACKING_LOST_DURING_REP);
        }
    }

    private void reject(ExerciseCounter.Reason cause) {
        rejected++;
        lastRejection = cause;
        pendingRejection = ExerciseCounter.Reason.NONE;
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
