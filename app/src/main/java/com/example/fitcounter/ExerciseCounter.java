package com.example.fitcounter;

final class ExerciseCounter {
    enum Phase { FIND_START, READY, LOWERING, BOTTOM, RISING }

    private static final long MAX_GAP_MS = 900;
    private static final long MAX_CYCLE_MS = 12_000;
    private static final double HYSTERESIS = 8;

    private final int straight;
    private final int depth;
    private Phase phase = Phase.FIND_START;
    private long lastTime = -1;
    private long cycleStart;
    private int stable;

    ExerciseCounter(int straight, int depth) {
        if (!validThresholds(straight, depth)) {
            throw new IllegalArgumentException("Invalid exercise angles");
        }
        this.straight = straight;
        this.depth = depth;
    }

    static boolean validThresholds(int straight, int depth) {
        return depth >= 45 && depth <= 150 && straight >= 75 && straight <= 180
                && straight - depth >= 30;
    }

    Phase phase() {
        return phase;
    }

    void reset() {
        phase = Phase.FIND_START;
        stable = 0;
        lastTime = -1;
    }

    boolean accept(long time, double angle, boolean startPosition, boolean bottomPosition) {
        if (Double.isNaN(angle) || Double.isInfinite(angle) || angle < 0 || angle > 180) {
            reset();
            return false;
        }
        if (lastTime >= 0 && (time <= lastTime || time - lastTime > MAX_GAP_MS)
                || (phase == Phase.LOWERING || phase == Phase.BOTTOM || phase == Phase.RISING)
                && time - cycleStart > MAX_CYCLE_MS) {
            reset();
        }
        lastTime = time;
        boolean atTop = angle >= straight && startPosition;
        switch (phase) {
            case FIND_START:
                if (confirmed(atTop)) transition(Phase.READY);
                break;
            case READY:
                if (angle < straight - HYSTERESIS) {
                    cycleStart = time;
                    transition(Phase.LOWERING);
                    confirmed(angle <= depth && bottomPosition);
                }
                break;
            case LOWERING:
                if (atTop) {
                    transition(Phase.READY);
                } else if (confirmed(angle <= depth && bottomPosition)) {
                    transition(Phase.BOTTOM);
                }
                break;
            case BOTTOM:
                if (angle > depth + HYSTERESIS) {
                    transition(Phase.RISING);
                    confirmed(atTop);
                }
                break;
            case RISING:
                if (angle <= depth) {
                    transition(Phase.BOTTOM);
                } else if (confirmed(atTop)) {
                    transition(Phase.READY);
                    return true;
                }
                break;
        }
        return false;
    }

    private boolean confirmed(boolean condition) {
        stable = condition ? stable + 1 : 0;
        return stable >= 2;
    }

    private void transition(Phase next) {
        phase = next;
        stable = 0;
    }
}
