package com.example.fitcounter;

enum Exercise {
    PUSH_UP(R.string.push_up, R.string.push_up_hint, 160, 95, 5, 6, 7, 8, 9, 10),
    SQUAT(R.string.squat, R.string.squat_hint, 165, 100, 11, 12, 13, 14, 15, 16);

    final int label, hint, straight, depth;
    private final int[] joints;

    Exercise(int label, int hint, int straight, int depth, int... joints) {
        this.label = label;
        this.hint = hint;
        this.straight = straight;
        this.depth = depth;
        this.joints = joints;
    }

    int joint(int position, int side) {
        return joints[2 * position + side];
    }
}
