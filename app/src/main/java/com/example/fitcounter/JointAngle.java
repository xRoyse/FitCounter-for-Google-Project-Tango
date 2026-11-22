package com.example.fitcounter;

final class JointAngle {
    private JointAngle() {}

    static double degrees(double ax, double ay, double bx, double by, double cx, double cy) {
        double ux = ax - bx;
        double uy = ay - by;
        double vx = cx - bx;
        double vy = cy - by;
        if (Math.hypot(ux, uy) < 1e-6 || Math.hypot(vx, vy) < 1e-6) return Double.NaN;
        return Math.toDegrees(Math.atan2(Math.abs(ux * vy - uy * vx), ux * vx + uy * vy));
    }
}
