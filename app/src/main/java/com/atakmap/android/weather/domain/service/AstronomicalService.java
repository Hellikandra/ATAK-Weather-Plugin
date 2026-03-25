package com.atakmap.android.weather.domain.service;

import com.atakmap.coremap.log.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Astronomical calculations for sunrise/sunset, twilight, and moon phase.
 *
 * <p>Sprint 9 (S9.2): Provides sun/moon data derived from Open-Meteo daily
 * forecast fields or computed locally.</p>
 *
 * <h3>BMNT / EENT</h3>
 * <ul>
 *   <li><b>BMNT</b> (Begin Morning Nautical Twilight) — approx 60 minutes before sunrise.</li>
 *   <li><b>EENT</b> (End Evening Nautical Twilight) — approx 60 minutes after sunset.</li>
 * </ul>
 *
 * <h3>Moon Phase</h3>
 * Uses the Trig2 algorithm based on the synodic period (29.53059 days) with
 * reference new moon = January 6, 2000 00:00 UTC.
 */
public class AstronomicalService {

    private static final String TAG = "AstronomicalService";

    /** Synodic period (new moon to new moon) in days. */
    private static final double SYNODIC_PERIOD = 29.53059;

    /** Reference new moon: January 6, 2000 00:00 UTC (Julian day ~2451550.1). */
    private static final long REF_NEW_MOON_MS;
    static {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(2000, Calendar.JANUARY, 6, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        REF_NEW_MOON_MS = cal.getTimeInMillis();
    }

    /** BMNT offset: 60 minutes before sunrise. */
    private static final long BMNT_OFFSET_MS = 60L * 60L * 1000L;

    /** EENT offset: 60 minutes after sunset. */
    private static final long EENT_OFFSET_MS = 60L * 60L * 1000L;

    private AstronomicalService() { /* static utility */ }

    // ═══════════════════════════════════════════════════════════════════════════
    // Sun times
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parse sunrise/sunset from Open-Meteo daily ISO strings.
     *
     * @param sunriseIso  ISO-8601 local time string, e.g. "2024-07-27T06:42"
     * @param sunsetIso   ISO-8601 local time string, e.g. "2024-07-27T20:15"
     * @return parsed {@link SunTimes}, or null if parsing fails
     */
    public static SunTimes parseSunTimes(String sunriseIso, String sunsetIso) {
        if (sunriseIso == null || sunsetIso == null) return null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            Date sunriseDate = sdf.parse(sunriseIso);
            Date sunsetDate  = sdf.parse(sunsetIso);
            if (sunriseDate == null || sunsetDate == null) return null;

            long sunriseMs = sunriseDate.getTime();
            long sunsetMs  = sunsetDate.getTime();
            long daylightMs = sunsetMs - sunriseMs;
            double daylightSec = daylightMs / 1000.0;

            long bmnt = calculateBMNT(sunriseMs);
            long eent = calculateEENT(sunsetMs);

            return new SunTimes(sunriseMs, sunsetMs, daylightSec, bmnt, eent);
        } catch (Exception e) {
            Log.w(TAG, "parseSunTimes failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Calculate BMNT (Begin Morning Nautical Twilight).
     * Approximation: 60 minutes before sunrise.
     *
     * @param sunriseEpochMs sunrise time in milliseconds since epoch
     * @return BMNT time in milliseconds since epoch
     */
    public static long calculateBMNT(long sunriseEpochMs) {
        return sunriseEpochMs - BMNT_OFFSET_MS;
    }

    /**
     * Calculate EENT (End Evening Nautical Twilight).
     * Approximation: 60 minutes after sunset.
     *
     * @param sunsetEpochMs sunset time in milliseconds since epoch
     * @return EENT time in milliseconds since epoch
     */
    public static long calculateEENT(long sunsetEpochMs) {
        return sunsetEpochMs + EENT_OFFSET_MS;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Moon phase
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calculate moon phase for a given date.
     * Uses the simple synodic-period algorithm with reference new moon = Jan 6, 2000.
     *
     * @param dateEpochMs date in milliseconds since epoch
     * @return {@link MoonPhase} with day-in-cycle, phase name, illumination, and emoji
     */
    public static MoonPhase calculateMoonPhase(long dateEpochMs) {
        double daysSinceRef = (dateEpochMs - REF_NEW_MOON_MS) / (24.0 * 60.0 * 60.0 * 1000.0);
        double cyclePos = daysSinceRef % SYNODIC_PERIOD;
        if (cyclePos < 0) cyclePos += SYNODIC_PERIOD;

        double dayInCycle = cyclePos;

        // Illumination: approximate using cosine
        // 0 at new moon, 1 at full moon (day ~14.76)
        double phase = cyclePos / SYNODIC_PERIOD; // 0..1
        double illumination = (1.0 - Math.cos(2.0 * Math.PI * phase)) / 2.0;
        int illuminationPct = (int) Math.round(illumination * 100.0);

        // Phase name and emoji
        String phaseName;
        String emoji;
        if (dayInCycle < 1.85) {
            phaseName = "New Moon";       emoji = "\uD83C\uDF11";
        } else if (dayInCycle < 7.38) {
            phaseName = "Waxing Crescent"; emoji = "\uD83C\uDF12";
        } else if (dayInCycle < 9.23) {
            phaseName = "First Quarter";   emoji = "\uD83C\uDF13";
        } else if (dayInCycle < 14.76) {
            phaseName = "Waxing Gibbous";  emoji = "\uD83C\uDF14";
        } else if (dayInCycle < 16.61) {
            phaseName = "Full Moon";        emoji = "\uD83C\uDF15";
        } else if (dayInCycle < 22.14) {
            phaseName = "Waning Gibbous";  emoji = "\uD83C\uDF16";
        } else if (dayInCycle < 23.99) {
            phaseName = "Last Quarter";    emoji = "\uD83C\uDF17";
        } else if (dayInCycle < 27.68) {
            phaseName = "Waning Crescent"; emoji = "\uD83C\uDF18";
        } else {
            phaseName = "New Moon";        emoji = "\uD83C\uDF11";
        }

        return new MoonPhase(dayInCycle, phaseName, illuminationPct, emoji);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Formatting
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Format epoch millis as HH:mm in local timezone.
     *
     * @param epochMs time in milliseconds since epoch
     * @return formatted string, e.g. "06:42"
     */
    public static String formatTime(long epochMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
        return sdf.format(new Date(epochMs));
    }

    /**
     * Format a daylight duration in seconds as "Xh Ym".
     *
     * @param durationSec daylight duration in seconds
     * @return formatted string, e.g. "13h 33m"
     */
    public static String formatDuration(double durationSec) {
        int totalMin = (int) (durationSec / 60.0);
        int hours = totalMin / 60;
        int mins  = totalMin % 60;
        return String.format(Locale.US, "%dh %02dm", hours, mins);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Inner classes
    // ═══════════════════════════════════════════════════════════════════════════

    /** Sunrise, sunset, and twilight times. */
    public static class SunTimes {
        public final long   sunrise;            // epoch ms
        public final long   sunset;             // epoch ms
        public final double daylightDurationSec;
        public final long   bmnt;               // Begin Morning Nautical Twilight
        public final long   eent;               // End Evening Nautical Twilight

        public SunTimes(long sunrise, long sunset, double daylightDurationSec,
                        long bmnt, long eent) {
            this.sunrise = sunrise;
            this.sunset  = sunset;
            this.daylightDurationSec = daylightDurationSec;
            this.bmnt = bmnt;
            this.eent = eent;
        }
    }

    /** Moon phase information. */
    public static class MoonPhase {
        public final double dayInCycle;         // 0..29.53
        public final String phaseName;          // e.g. "Waxing Crescent"
        public final int    illuminationPercent; // 0..100
        public final String emoji;              // e.g. moon emoji

        public MoonPhase(double dayInCycle, String phaseName,
                         int illuminationPercent, String emoji) {
            this.dayInCycle = dayInCycle;
            this.phaseName  = phaseName;
            this.illuminationPercent = illuminationPercent;
            this.emoji = emoji;
        }
    }
}
