package com.example.fitcounter;

final class TrackingStatus {
    enum State { ACQUIRING, TRACKING, PARTIALLY_VISIBLE, NO_PERSON, STALE }

    private static final long SHOW_WARNING_MS = 650;
    private long missingSince = -1;
    private int goodFrames;
    private boolean partial;
    private State state = State.ACQUIRING;

    State state() { return state; }

    void update(long time, boolean person, boolean joints) {
        if (joints) {
            missingSince = -1;
            if (++goodFrames >= 2) state = State.TRACKING;
            return;
        }
        goodFrames = 0;
        boolean warning = state == State.NO_PERSON || state == State.PARTIALLY_VISIBLE;
        if (missingSince < 0 || warning && partial != person) missingSince = time;
        partial = person;
        if (time - missingSince >= SHOW_WARNING_MS) {
            state = person ? State.PARTIALLY_VISIBLE : State.NO_PERSON;
        }
    }

    void reset() {
        state = State.ACQUIRING;
        missingSince = -1;
        goodFrames = 0;
    }

    void expire() {
        reset();
        state = State.STALE;
    }
}
