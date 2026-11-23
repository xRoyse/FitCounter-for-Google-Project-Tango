package com.example.fitcounter;

final class TrackingStatus {
    enum State { ACQUIRING, TRACKING, PARTIALLY_VISIBLE, NO_PERSON, STALE }

    private static final long SHOW_WARNING_MS = 650;
    private long missingSince = -1;
    private long kindSince = -1;
    private int goodFrames;
    private boolean partial;
    private State state = State.ACQUIRING;

    State state() { return state; }
    long missingSince() { return missingSince; }

    void update(long time, boolean person, boolean joints) {
        if (joints) {
            if (goodFrames < 2) goodFrames++;
            if (goodFrames >= 2) {
                state = State.TRACKING;
                missingSince = kindSince = -1;
            }
            return;
        }
        goodFrames = 0;
        boolean warning = state == State.NO_PERSON || state == State.PARTIALLY_VISIBLE;
        if (missingSince < 0) missingSince = time;
        if (kindSince < 0 || partial != person) kindSince = time;
        partial = person;
        if (time - (warning ? kindSince : missingSince) >= SHOW_WARNING_MS) {
            state = person ? State.PARTIALLY_VISIBLE : State.NO_PERSON;
        }
    }

    void reset() {
        state = State.ACQUIRING;
        missingSince = kindSince = -1;
        goodFrames = 0;
    }

    void expire() {
        reset();
        state = State.STALE;
    }
}
