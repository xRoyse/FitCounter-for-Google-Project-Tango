package com.example.fitcounter;

import org.junit.Test;
import java.util.Calendar;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class ExampleUnitTest {
    @Test public void calculatesAnglesAndRejectsDegenerateSegments() {
        assertEquals(90, JointAngle.degrees(1, 0, 0, 0, 0, 1), 0.000001);
        assertEquals(180, JointAngle.degrees(-1, 0, 0, 0, 1, 0), 0.000001);
        assertEquals(0, JointAngle.degrees(1, 0, 0, 0, 2, 0), 0.000001);
        assertTrue(Double.isNaN(JointAngle.degrees(0, 0, 0, 0, 2, 0)));
    }

    @Test public void countsCompletePushUpOnce() {
        ExerciseCounter counter = new ExerciseCounter(160, 95);
        assertEquals(1, feed(counter, 170, 172, 151, 120, 92, 91, 110, 155, 168, 170, 170, 168));
        assertEquals(ExerciseCounter.Phase.READY, counter.phase());
    }

    @Test public void countsTwoCompleteSquatsInOrder() {
        ExerciseCounter counter = new ExerciseCounter(165, 100);
        assertEquals(2, feed(counter, 176, 178, 145, 96, 94, 122, 169, 171,
                145, 96, 94, 122, 169, 171));
    }

    @Test public void rejectsShallowPushUpAndShallowSquat() {
        assertEquals(0, feed(new ExerciseCounter(160, 95), 168, 170, 140, 110, 103, 125, 165, 170));
        assertEquals(0, feed(new ExerciseCounter(165, 100), 171, 173, 155, 134, 105, 129, 170, 171));
    }

    @Test public void singleDeepOutlierAndJitterDoNotCount() {
        assertEquals(0, feed(new ExerciseCounter(160, 95),
                168, 170, 158, 162, 154, 158, 120, 83, 121, 166, 171));
    }

    @Test public void poseLossOrLargeGapRequiresNewStartingPosition() {
        ExerciseCounter lost = new ExerciseCounter(160, 95);
        assertEquals(0, feed(lost, 170, 171, 125, 92, 90, Double.NaN, 120, 171, 172));
        ExerciseCounter gap = new ExerciseCounter(160, 95);
        feed(gap, 170, 171, 125, 92, 90);
        assertFalse(gap.accept(2200, 130, true, true));
        assertFalse(gap.accept(2350, 171, true, true));
        assertFalse(gap.accept(2500, 174, true, true));
        assertEquals(ExerciseCounter.Phase.READY, gap.phase());
    }

    @Test public void invalidBodyPositionDoesNotArmOrConfirmDepth() {
        ExerciseCounter counter = new ExerciseCounter(165, 100);
        counter.accept(100, 175, false, true);
        counter.accept(250, 177, false, true);
        assertEquals(ExerciseCounter.Phase.FIND_START, counter.phase());
        counter.accept(400, 175, true, true);
        counter.accept(550, 177, true, true);
        counter.accept(700, 140, true, false);
        counter.accept(850, 90, true, false);
        counter.accept(1000, 92, true, false);
        assertFalse(counter.accept(1150, 177, true, true));
        assertFalse(counter.accept(1300, 177, true, true));
        assertEquals(ExerciseCounter.Phase.READY, counter.phase());
    }

    @Test public void mapsAllSensorRotationsAndInvertsLetterbox() {
        int[][] corners = {{0, 3, 8, 11}, {8, 0, 11, 3}, {11, 8, 3, 0}, {3, 11, 0, 8}};
        for (int turn = 0; turn < 4; turn++) {
            FrameTransform t = new FrameTransform(4, 3, 90 * turn, 192);
            int xmax = t.uprightWidth - 1, ymax = t.uprightHeight - 1;
            assertArrayEquals(corners[turn], new int[]{t.sensorIndex(0, 0),
                    t.sensorIndex(xmax, 0), t.sensorIndex(0, ymax), t.sensorIndex(xmax, ymax)});
            assertEquals(0, t.uprightX(t.left / 192), 0.000001);
            assertEquals(t.uprightHeight, t.uprightY((t.top + t.uprightHeight * t.scale) / 192),
                    0.000001);
        }
    }

    @Test public void usesLocalCalendarDaysAcrossDaylightSavingAndYearBoundary() {
        TimeZone zone = TimeZone.getTimeZone("America/New_York");
        Calendar cal = Calendar.getInstance(zone);
        cal.clear();
        cal.set(2024, Calendar.MARCH, 10, 13, 0);
        Calendar day = DateRanges.day(cal.getTimeInMillis(), zone);
        assertEquals(0, day.get(Calendar.HOUR_OF_DAY));
        assertEquals(23 * 60 * 60 * 1000L,
                DateRanges.next(day, Calendar.DAY_OF_MONTH, 1) - day.getTimeInMillis());
        cal.set(2023, Calendar.JANUARY, 1, 12, 0);
        Calendar week = DateRanges.week(DateRanges.day(cal.getTimeInMillis(), zone));
        assertEquals(Calendar.MONDAY, week.get(Calendar.DAY_OF_WEEK));
        assertEquals(26, week.get(Calendar.DAY_OF_MONTH));
        assertEquals(2022, week.get(Calendar.YEAR));
    }

    private static int feed(ExerciseCounter counter, double... angles) {
        int count = 0;
        for (int i = 0; i < angles.length; i++) {
            if (counter.accept(100 + 150L * i, angles[i], true, true)) count++;
        }
        return count;
    }
}
