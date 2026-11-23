package com.example.fitcounter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PoseTrackingTest {
    @Test public void retriesACroppedTrackingFailureOnTheSameFrame() {
        PoseEstimator estimator = new PoseEstimator() {
            @Override public int size() { return 192; }
            @Override public Pose estimate(Frame frame, FrameTransform transform) {
                return transform.cropSize == 0 ? body(100) : new Pose();
            }
            @Override public long preprocessingNanos() { return 1; }
            @Override public long inferenceNanos() { return 2; }
            @Override public void close() {}
        };
        PoseAnalyzer analyzer = new PoseAnalyzer(estimator);
        Frame first = new Frame(new byte[0], 640, 480, 0, 100, () -> {});
        Frame second = new Frame(new byte[0], 640, 480, 0, 200, () -> {});
        Frame third = new Frame(new byte[0], 640, 480, 0, 300, () -> {});
        assertNull(analyzer.analyze(first).pose);
        assertNotNull(analyzer.analyze(second).pose);
        PoseReading result = analyzer.analyze(third);
        assertTrue(result.fullFrameRetry);
        assertNotNull(result.pose);
        assertEquals(third.id, result.frameId);
        assertEquals(4, result.inferenceNanos);
        Frame fourth = new Frame(new byte[0], 640, 480, 0, 400, () -> {});
        assertFalse(analyzer.analyze(fourth).fullFrameRetry);
    }

    @Test public void confirmsCoherentBodyTwiceAndDoesNotDrawIsolatedPoints() {
        PoseValidator validator = new PoseValidator();
        FrameTransform frame = new FrameTransform(320, 240, 0, 192);
        Pose clutter = new Pose();
        for (int i = 0; i < Pose.SIZE; i++) joint(clutter, i, 100 + i % 5, 100 + i / 5);
        assertFalse(validator.accept(clutter, frame, 0));
        assertFalse(validator.accept(clutter, frame, 125));
        assertFalse(validator.accept(body(30), frame, 250));
        assertTrue(validator.accept(body(32), frame, 375));
        assertFalse(validator.accept(new Pose(), frame, 500));
        assertTrue(validator.accept(body(34), frame, 625));
        assertFalse(validator.accept(body(220), frame, 750));
    }

    @Test public void usesTheCoherentTorsoWhenTheHigherScoringSideCollapses() {
        PoseValidator validator = new PoseValidator();
        FrameTransform frame = new FrameTransform(320, 240, 0, 192);
        Pose person = body(100);
        person.x[6] = person.x[12] = 110;
        person.y[6] = person.y[12] = 75;
        person.confidence[6] = person.confidence[12] = .9f;
        assertFalse(validator.accept(person, frame, 0));
        assertTrue(validator.accept(person, frame, 125));
    }

    @Test public void shortLossKeepsStatusAndRecoveryRequiresTwoMeasurements() {
        TrackingStatus tracking = new TrackingStatus();
        tracking.update(0, true, true);
        tracking.update(120, true, true);
        tracking.update(240, true, false);
        tracking.update(540, false, false);
        assertEquals(TrackingStatus.State.TRACKING, tracking.state());
        tracking.update(940, true, false);
        assertEquals(TrackingStatus.State.PARTIALLY_VISIBLE, tracking.state());
        tracking.update(1060, false, false);
        tracking.update(1180, true, true);
        assertEquals(TrackingStatus.State.PARTIALLY_VISIBLE, tracking.state());
        tracking.update(1300, true, true);
        assertEquals(TrackingStatus.State.TRACKING, tracking.state());
    }

    @Test public void shortLossCanResumeObservedBottomAndNeverInventsDepth() {
        ExerciseCounter counter = new ExerciseCounter(160, 95);
        counter.accept(0, 175, true, true);
        counter.accept(100, 175, true, true);
        counter.accept(200, 115, true, true);
        counter.accept(300, 90, true, true);
        counter.accept(400, 92, true, true);
        assertEquals(ExerciseCounter.Phase.BOTTOM, counter.phase());
        counter.missing(500);
        assertEquals(ExerciseCounter.Phase.BOTTOM, counter.phase());
        assertFalse(counter.accept(600, 112, true, true));
        assertEquals(ExerciseCounter.Phase.RISING, counter.phase());
        assertFalse(counter.accept(700, 127, true, true));
        assertFalse(counter.accept(800, 168, true, true));
        assertTrue(counter.accept(900, 172, true, true));
        counter.accept(1000, 135, true, true);
        counter.missing(1100);
        assertFalse(counter.accept(1200, 90, true, true));
        assertFalse(counter.accept(1300, 170, true, true));
        assertFalse(counter.accept(1400, 170, true, true));
    }

    @Test public void confirmsTwoMeasuredBottomsSeparatedByOneMissingFrame() {
        ExerciseCounter counter = new ExerciseCounter(165, 100);
        counter.accept(0, 173, true, false);
        counter.accept(125, 174, true, false);
        counter.accept(250, 135, false, false);
        counter.accept(375, 91, false, true);
        counter.missing(500);
        assertFalse(counter.accept(625, 93, false, true));
        assertEquals(ExerciseCounter.Phase.BOTTOM, counter.phase());
        counter.accept(750, 119, false, true);
        assertFalse(counter.accept(875, 171, true, false));
        assertTrue(counter.accept(1000, 175, true, false));
    }

    @Test public void unstableRecoveryDoesNotPostponeWarningIndefinitely() {
        TrackingStatus tracking = new TrackingStatus();
        tracking.update(0, true, true);
        tracking.update(125, true, true);
        for (int i = 2; i <= 8; i++) tracking.update(i * 125, true, i % 2 != 0);
        assertEquals(TrackingStatus.State.PARTIALLY_VISIBLE, tracking.state());
        assertEquals(250, tracking.missingSince());
        tracking.update(1125, true, true);
        tracking.update(1250, true, true);
        assertEquals(TrackingStatus.State.TRACKING, tracking.state());
    }

    @Test public void reportsShallowMotionAndTrackingLossOnlyAfterDescent() {
        ExerciseCounter counter = new ExerciseCounter(165, 100);
        counter.accept(0, 174, true, false);
        counter.accept(125, 174, true, false);
        counter.accept(250, 147, true, false);
        counter.accept(375, 109, false, true);
        assertFalse(counter.accept(500, 174, true, false));
        assertEquals(ExerciseCounter.Reason.BOTTOM_POSITION_NOT_REACHED, counter.rejection);
        counter.accept(625, 141, false, true);
        counter.missing(750);
        assertEquals(ExerciseCounter.Reason.NONE, counter.rejection);
        counter.missing(1125);
        assertEquals(ExerciseCounter.Reason.TRACKING_LOST_DURING_REP, counter.rejection);
        counter.missing(1250);
        assertEquals(ExerciseCounter.Reason.NONE, counter.rejection);
    }

    @Test public void reacquiringTheTopPreservesReadinessButSkippingTheBottomCannotCount() {
        ExerciseCounter counter = new ExerciseCounter(160, 95);
        counter.accept(0, 170, true, false);
        counter.accept(125, 170, true, false);
        counter.missing(250);
        counter.missing(375);
        counter.missing(500);
        assertFalse(counter.accept(625, 170, true, false));
        assertEquals(ExerciseCounter.Phase.READY, counter.phase());
        counter.missing(750);
        counter.missing(875);
        counter.missing(1000);
        assertFalse(counter.accept(1125, 90, true, true));
        assertEquals(ExerciseCounter.Phase.FIND_START, counter.phase());
        assertFalse(counter.accept(1250, 170, true, false));
        assertFalse(counter.accept(1375, 170, true, false));
    }

    @Test public void mapsNv21AndCropForAllSensorRotationsAndBothModels() {
        int w = 6, h = 4, input = 8;
        byte[] nv21 = new byte[w * h * 3 / 2];
        for (int i = 0; i < nv21.length; i++) nv21[i] = (byte) (40 + i * 5);
        for (int rotation = 0; rotation < 360; rotation += 90) {
            ByteBuffer rgb = ByteBuffer.allocate(input * input * 3);
            ByteBuffer floats = ByteBuffer.allocate(input * input * 3 * 4).order(ByteOrder.nativeOrder());
            FramePreprocessor uint = new FramePreprocessor(input, rgb, false);
            FramePreprocessor normalized = new FramePreprocessor(input, floats, true);
            Frame frame = new Frame(nv21, w, h, rotation, 0, () -> {});
            for (FrameTransform t : new FrameTransform[]{
                    new FrameTransform(w, h, rotation, input),
                    new FrameTransform(w, h, rotation, input, -1, 1, 4),
                    new FrameTransform(w, h, rotation, input)}) {
                uint.fill(frame, t);
                normalized.fill(frame, t);
                for (int row = 0; row < input; row++) {
                    for (int col = 0; col < input; col++) {
                        float x = t.uprightX((col + 0.5f) / input);
                        float y = t.uprightY((row + 0.5f) / input);
                        int[] expected = new int[3];
                        if (x >= 0 && x < t.uprightWidth && y >= 0 && y < t.uprightHeight) {
                            int p = t.sensorIndex((int) x, (int) y);
                            int uv = w * h + (p / w / 2) * w + (p % w & ~1);
                            int v = (nv21[uv] & 255) - 128, u = (nv21[uv + 1] & 255) - 128;
                            int luma = Math.max(0, (nv21[p] & 255) - 16) * 298;
                            expected[0] = clip((luma + 409 * v + 128) >> 8);
                            expected[1] = clip((luma - 100 * u - 208 * v + 128) >> 8);
                            expected[2] = clip((luma + 516 * u + 128) >> 8);
                        }
                        int index = (row * input + col) * 3;
                        for (int c = 0; c < 3; c++) {
                            assertEquals(expected[c], rgb.get(index + c) & 255);
                            assertEquals((expected[c] - 128) / 128f, floats.getFloat((index + c) * 4), 0);
                        }
                    }
                }
            }
        }
    }

    private static int clip(int value) { return Math.min(255, Math.max(0, value)); }

    private static Pose body(int offset) {
        Pose p = new Pose();
        joint(p, 0, offset, 22);
        joint(p, 5, offset, 46);
        joint(p, 7, offset - 12, 70);
        joint(p, 9, offset - 13, 94);
        joint(p, 11, offset + 3, 93);
        joint(p, 13, offset + 11, 127);
        joint(p, 15, offset + 4, 158);
        return p;
    }

    private static void joint(Pose p, int j, int x, int y) {
        p.x[j] = x;
        p.y[j] = y;
        p.confidence[j] = 0.8f;
    }
}
