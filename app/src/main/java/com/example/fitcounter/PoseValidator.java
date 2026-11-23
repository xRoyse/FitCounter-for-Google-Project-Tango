package com.example.fitcounter;

final class PoseValidator {
    static final float DRAW_CONFIDENCE = 0.28f;
    static final float COUNT_CONFIDENCE = 0.30f;
    private static final int[] TORSO = {5, 6, 11, 12};
    private static final long CONTINUITY_MS = 550;

    private Evidence previous;
    private long previousTime, lastStrong;
    private int consecutive;
    private boolean tracked;
    String reason = "ACQUIRING";

    boolean accept(Pose pose, FrameTransform frame, long time) {
        return accept(inspect(pose, frame), time);
    }

    boolean accept(Evidence current, long time) {
        if (current == null) {
            if (!tracked || time - lastStrong > CONTINUITY_MS) reset();
            return false;
        }
        boolean jumped = previous != null && time > previousTime && time - previousTime <= CONTINUITY_MS
                && !previous.near(current, time - previousTime);
        if (jumped || previous != null && (time <= previousTime || time - previousTime > CONTINUITY_MS)) reset();
        if (current.strong) {
            lastStrong = time;
            tracked |= ++consecutive >= 2;
        } else {
            consecutive = 0;
            if (tracked && time - lastStrong > CONTINUITY_MS) reset();
        }
        previous = current;
        previousTime = time;
        reason = tracked ? "CONFIRMED" : jumped ? "BODY_JUMP" : current.strong
                ? "CONFIRMING_PERSON" : "WEAK_BODY_EVIDENCE";
        return tracked;
    }

    void reset() {
        previous = null;
        consecutive = 0;
        tracked = false;
    }

    Evidence inspect(Pose pose, FrameTransform frame) {
        reason = "PERSON_NOT_DETECTED";
        if (pose == null) return null;
        int invalid = 0;
        for (int i = 0; i < Pose.SIZE; i++) {
            if (!(pose.x[i] >= 0 && pose.x[i] < frame.uprightWidth
                    && pose.y[i] >= 0 && pose.y[i] < frame.uprightHeight
                    && pose.confidence[i] >= 0 && pose.confidence[i] <= 1)) {
                if (pose.confidence[i] >= DRAW_CONFIDENCE) invalid |= 1 << i;
                pose.confidence[i] = 0;
            }
        }
        float left = Math.min(pose.confidence[5], pose.confidence[11]);
        float right = Math.min(pose.confidence[6], pose.confidence[12]);
        int side = left >= right ? 0 : 1;
        double minTorso = Math.min(frame.uprightWidth, frame.uprightHeight) * 0.045;
        if (pose.distance(5 + side, 11 + side) < minTorso
                && Math.min(left, right) >= DRAW_CONFIDENCE) {
            // При наложении суставов дальней стороны опираемся на вторую видимую сторону.
            side = 1 - side;
        }
        int shoulder = 5 + side, hip = 11 + side;
        if (!pose.visible(shoulder, DRAW_CONFIDENCE) || !pose.visible(hip, DRAW_CONFIDENCE)) {
            reason = (invalid & ((3 << 5) | (3 << 11))) != 0 ? "INVALID_COORDINATES" : "LOW_TORSO_CONFIDENCE";
            return null;
        }
        reason = "INVALID_BODY_GEOMETRY";
        double torso = pose.distance(shoulder, hip);
        if (torso < minTorso) return null;
        int anchors = 0, support = 0, usable = 0;
        float score = 0;
        for (int joint : TORSO) {
            if (pose.visible(joint, DRAW_CONFIDENCE)) anchors++;
        }
        for (int i = 0; i < 2; i++) {
            support += chain(pose, 5 + i, 7 + i, 9 + i, torso);
            support += chain(pose, 11 + i, 13 + i, 15 + i, torso);
        }
        for (int i = 5; i < Pose.SIZE; i++) {
            if (pose.visible(i, DRAW_CONFIDENCE)) {
                usable++;
                score += pose.confidence[i];
            }
        }
        if (anchors < 2 || usable < 4 || support < 2) return null;
        boolean face = false;
        for (int i = 0; i < 5; i++) {
            face |= pose.visible(i, 0.38f) && pose.distance(i, shoulder) < 1.15 * torso;
        }
        // В профиль дальние суставы могут совпадать с ближними или перекрываться.
        // Достаточно связных сегментов и независимого подтверждения по торсу, голове или второй цепочке.
        boolean strong = usable >= 5 && score / usable >= 0.40f
                && (anchors >= 3 && support >= 3 || face && support >= 3 || support >= 4);
        return new Evidence((pose.x[shoulder] + pose.x[hip]) * 0.5,
                (pose.y[shoulder] + pose.y[hip]) * 0.5, torso, strong);
    }

    private static int chain(Pose pose, int a, int b, int c, double torso) {
        if (!pose.visible(a, DRAW_CONFIDENCE) || !pose.visible(b, DRAW_CONFIDENCE)) return 0;
        double first = pose.distance(a, b);
        if (first < 0.08 * torso || first > 2.1 * torso) return 0;
        if (!pose.visible(c, DRAW_CONFIDENCE)) return 1;
        double second = pose.distance(b, c);
        if (second < 0.08 * torso || second > 2.1 * torso
                || second > first * 4.5 || first > second * 4.5) {
            pose.confidence[c] = 0;
            return 1;
        }
        return 2;
    }

    static final class Evidence {
        final double x, y, torso;
        final boolean strong;

        Evidence(double x, double y, double torso, boolean strong) {
            this.x = x;
            this.y = y;
            this.torso = torso;
            this.strong = strong;
        }

        boolean near(Evidence other, long elapsed) {
            double scale = Math.max(torso, other.torso);
            return scale <= 2.5 * Math.min(torso, other.torso)
                    && Math.hypot(x - other.x, y - other.y) <= scale * (0.55 + elapsed / 350.0);
        }
    }
}
