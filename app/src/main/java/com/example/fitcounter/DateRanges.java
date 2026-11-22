package com.example.fitcounter;

import java.util.Calendar;
import java.util.TimeZone;

final class DateRanges {
    private DateRanges() {}

    static Calendar day(long instant, TimeZone zone) {
        Calendar date = Calendar.getInstance(zone);
        date.setTimeInMillis(instant);
        date.set(Calendar.HOUR_OF_DAY, 0);
        date.set(Calendar.MINUTE, 0);
        date.set(Calendar.SECOND, 0);
        date.set(Calendar.MILLISECOND, 0);
        return date;
    }

    static Calendar month(Calendar date) {
        Calendar start = (Calendar) date.clone();
        start.set(Calendar.DAY_OF_MONTH, 1);
        return day(start.getTimeInMillis(), start.getTimeZone());
    }

    static Calendar week(Calendar date) {
        Calendar start = (Calendar) date.clone();
        int offset = (start.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7;
        start.add(Calendar.DAY_OF_MONTH, -offset);
        return day(start.getTimeInMillis(), start.getTimeZone());
    }

    static long next(Calendar start, int field, int amount) {
        Calendar end = (Calendar) start.clone();
        end.add(field, amount);
        return day(end.getTimeInMillis(), end.getTimeZone()).getTimeInMillis();
    }
}
