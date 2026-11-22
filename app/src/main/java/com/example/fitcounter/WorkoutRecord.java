package com.example.fitcounter;

final class WorkoutRecord {
    final String id;
    final long finishedAt;
    final Exercise exercise;
    final int repetitions;

    WorkoutRecord(String id, long finishedAt, Exercise exercise, int repetitions) {
        this.id = id;
        this.finishedAt = finishedAt;
        this.exercise = exercise;
        this.repetitions = repetitions;
    }
}
