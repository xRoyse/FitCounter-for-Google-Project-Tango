package com.example.fitcounter;

final class ExerciseCounter {
    enum Phase { FIND_START, READY, LOWERING, BOTTOM, RISING }
    enum Reason {
        NONE, START_ANGLE_NOT_REACHED, START_GEOMETRY, CONFIRMING_START,
        WAITING_FOR_DESCENT, BOTTOM_POSITION_NOT_REACHED, INSUFFICIENT_DISPLACEMENT,
        CONFIRMING_BOTTOM, BOTTOM_NOT_CONFIRMED, WAITING_FOR_ASCENT, RETURN_TO_TOP_NOT_CONFIRMED,
        TRACKING_GAP, TRACKING_LOST_DURING_REP, CYCLE_TIMEOUT
    }

    private static final long MAX_GAP_MS = 450;
    private static final long MAX_SAMPLE_INTERVAL_MS = 550;
    private static final long BOTTOM_CONFIRMATION_MS = 260;
    private static final long MAX_CYCLE_MS = 12_000;
    private static final double HYSTERESIS = 8;

    private final int straight;
    private final int depth;
    private Phase phase = Phase.FIND_START;
    private long lastTime = -1;
    private long cycleStart;
    private long bottomEvidence = -1;
    private int stable;
    private boolean interrupted;
    Reason reason = Reason.NONE;
    Reason rejection = Reason.NONE;
    String direction = "STILL";
    private double previousAngle = Double.NaN;
    private boolean reachedDepth, reachedDisplacement;
    private boolean meaningfulDescent;

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
        bottomEvidence = -1;
        interrupted = false;
        reason = Reason.NONE;
        rejection = Reason.NONE;
        previousAngle = Double.NaN;
        direction = "STILL";
        reachedDepth = false;
        reachedDisplacement = false;
        meaningfulDescent = false;
    }

    boolean accept(long time, double angle, boolean startPosition, boolean bottomPosition) {
        rejection = Reason.NONE;
        if (Double.isNaN(angle) || Double.isInfinite(angle) || angle < 0 || angle > 180) {
            missing(time);
            return false;
        }
        if (lastTime >= 0 && (time <= lastTime || time - lastTime > MAX_SAMPLE_INTERVAL_MS)
                || (phase == Phase.LOWERING || phase == Phase.BOTTOM || phase == Phase.RISING)
                && time - cycleStart > MAX_CYCLE_MS) {
            invalidate(attemptActive() && time - cycleStart > MAX_CYCLE_MS
                    ? Reason.CYCLE_TIMEOUT : Reason.TRACKING_LOST_DURING_REP);
        }
        double difference = angle - previousAngle;
        direction = difference < -2 ? "DESCENDING" : difference > 2 ? "ASCENDING" : "STILL";
        previousAngle = angle;
        boolean atTop = angle >= straight && startPosition;
        if (interrupted) {
            // После пропуска не подтверждаем пропущенную нижнюю фазу или подъём.
            // Конечные положения всё равно требуют двух новых достоверных измерений.
            boolean samePhase;
            switch (phase) {
                case READY: samePhase = atTop || angle > depth + HYSTERESIS; break;
                case LOWERING: samePhase = !atTop; break;
                case RISING: samePhase = angle > depth + HYSTERESIS; break;
                case BOTTOM:
                    // Дно уже измерено. Промежуточный угол после пропуска подтверждает подъём.
                    samePhase = angle <= depth + HYSTERESIS ? bottomPosition
                            : angle < straight - HYSTERESIS;
                    break;
                default: samePhase = false;
            }
            // Для исходного положения можно использовать больший интервал между кадрами:
            // верх измерен до и после пропуска, полный цикл ещё предстоит наблюдать.
            boolean reacquiredTop = phase == Phase.READY && atTop;
            if (!samePhase || !reacquiredTop && time - lastTime > MAX_GAP_MS) {
                invalidate(Reason.TRACKING_LOST_DURING_REP);
                previousAngle = angle;
            } else {
                interrupted = false;
            }
        }
        lastTime = time;
        switch (phase) {
            case FIND_START:
                reason = angle < straight ? Reason.START_ANGLE_NOT_REACHED
                        : !startPosition ? Reason.START_GEOMETRY : Reason.CONFIRMING_START;
                if (confirmed(atTop)) transition(Phase.READY);
                break;
            case READY:
                reason = Reason.WAITING_FOR_DESCENT;
                if (angle < straight - HYSTERESIS) {
                    cycleStart = time;
                    reachedDepth = angle <= depth;
                    reachedDisplacement = reachedDepth && bottomPosition;
                    meaningfulDescent = angle <= straight - 2 * HYSTERESIS;
                    transition(Phase.LOWERING);
                    reason = Reason.BOTTOM_POSITION_NOT_REACHED;
                    confirmBottom(time, angle <= depth && bottomPosition);
                }
                break;
            case LOWERING:
                reachedDepth |= angle <= depth;
                reachedDisplacement |= angle <= depth && bottomPosition;
                meaningfulDescent |= angle <= straight - 2 * HYSTERESIS;
                if (atTop) {
                    if (meaningfulDescent) {
                        rejection = !reachedDepth ? Reason.BOTTOM_POSITION_NOT_REACHED
                                : reachedDisplacement ? Reason.BOTTOM_NOT_CONFIRMED : Reason.INSUFFICIENT_DISPLACEMENT;
                    }
                    transition(Phase.READY);
                } else if (confirmBottom(time, angle <= depth && bottomPosition)) {
                    transition(Phase.BOTTOM);
                    reason = Reason.WAITING_FOR_ASCENT;
                } else {
                    reason = angle > depth ? Reason.BOTTOM_POSITION_NOT_REACHED
                            : !bottomPosition ? Reason.INSUFFICIENT_DISPLACEMENT : Reason.CONFIRMING_BOTTOM;
                }
                break;
            case BOTTOM:
                reason = Reason.WAITING_FOR_ASCENT;
                if (angle > depth + HYSTERESIS) {
                    transition(Phase.RISING);
                    confirmed(atTop);
                }
                break;
            case RISING:
                reason = Reason.RETURN_TO_TOP_NOT_CONFIRMED;
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

    void synchronizeAfterRepetition(long time, boolean atTop) {
        reset();
        if (atTop) {
            lastTime = time;
            confirmed(true);
        }
    }

    void missing(long time) {
        rejection = Reason.NONE;
        previousAngle = Double.NaN;
        direction = "UNKNOWN";
        if (lastTime >= 0 && time - lastTime <= MAX_GAP_MS) {
            stable = 0;
            interrupted = true;
        } else {
            invalidate(Reason.TRACKING_LOST_DURING_REP);
        }
        reason = Reason.TRACKING_GAP;
    }

    boolean attemptActive() {
        return phase == Phase.LOWERING || phase == Phase.BOTTOM || phase == Phase.RISING;
    }

    boolean attemptedRep() { return meaningfulDescent && attemptActive(); }

    private void invalidate(Reason cause) {
        boolean attempted = attemptedRep();
        reset();
        if (attempted) rejection = cause;
    }

    private boolean confirmed(boolean condition) {
        stable = condition ? stable + 1 : 0;
        return stable >= 2;
    }

    private boolean confirmBottom(long time, boolean reliableBottom) {
        // Короткий пропуск между двумя реально измеренными нижними положениями
        // не отменяет первое измерение. Кадр с углом вне порога отменяет его.
        boolean confirmed = reliableBottom && bottomEvidence >= 0 && time - bottomEvidence <= BOTTOM_CONFIRMATION_MS;
        bottomEvidence = reliableBottom ? time : -1;
        return confirmed;
    }

    private void transition(Phase next) {
        phase = next;
        bottomEvidence = -1;
        if (next == Phase.READY) reason = Reason.WAITING_FOR_DESCENT;
        stable = 0;
    }
}
