package com.atakmap.android.weather.util;

import android.os.Build;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Date/time helpers to keep formatting logic out of ViewModels and Views.
 */
public final class DateUtils {

    private static final String PATTERN_REQUEST_TIME  = "yyyy-MM-dd HH:mm:ss";
    private static final String PATTERN_HOURLY_ISO    = "yyyy-MM-dd'T'HH:mm";
    private static final String PATTERN_DAILY_DATE    = "yyyy-MM-dd";

    private DateUtils() {}

    /** Current timestamp as a formatted string, e.g. "2024-07-27 14:30:00". */
    public static String nowFormatted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern(PATTERN_REQUEST_TIME));
        }
        return "";
    }

    /** Extract the hour integer (0-23) from an ISO hourly timestamp. */
    public static int hourFromIso(String isoTime) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return LocalDateTime.parse(isoTime,
                    DateTimeFormatter.ofPattern(PATTERN_HOURLY_ISO)).getHour();
        }
        return 0;
    }

    /**
     * Return day-of-week label from a daily date string.
     * Returns "Today" for index 0.
     */
    public static String dayLabel(String dateStr, boolean isToday) {
        if (isToday) return "Today";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LocalDate date = LocalDate.parse(dateStr,
                    DateTimeFormatter.ofPattern(PATTERN_DAILY_DATE));
            DayOfWeek dow = date.getDayOfWeek();
            // e.g. "MONDAY" → "Monday"
            return dow.getDisplayName(TextStyle.FULL, Locale.getDefault());
        }
        return dateStr;
    }
}
