package com.atakmap.android.weather.util;

import android.content.Context;
import android.widget.ArrayAdapter;

import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WindProfileModel;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

/**
 * Stateless UI utilities extracted from {@code WeatherDropDownReceiver}.
 *
 * <h3>Extracted from DDR</h3>
 * <ul>
 *   <li>{@link #makeDarkSpinnerAdapter} — was an instance method in DDR.</li>
 *   <li>{@link #isoDayOfWeek} — reimplemented {@link java.time} manually in DDR.</li>
 *   <li>{@link #buildMarkerUid} — was an instance method in DDR.</li>
 *   <li>{@link #buildAltitudeTierLabel} — was a static method in DDR.</li>
 * </ul>
 */
public final class WeatherUiUtils {

    private WeatherUiUtils() { /* utility class */ }

    /**
     * Create an {@link ArrayAdapter} suitable for ATAK plugin spinners.
     *
     * <p>ATAK's dark theme means the collapsed spinner sits on a dark background
     * (needs white text) while the expanded dropdown popup has a light background
     * (needs dark text). This adapter overrides {@code getDropDownView()} to
     * force dark text in the popup only.</p>
     *
     * @param context the application context (not the plugin context — avoids
     *                BadTokenException)
     * @param items   the list of items to display
     * @param <T>     item type
     * @return a correctly themed ArrayAdapter
     */
    public static <T> ArrayAdapter<T> makeDarkSpinnerAdapter(Context context,
                                                             List<T> items) {
        return new ArrayAdapter<T>(context,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public android.view.View getView(int pos, android.view.View conv,
                                             android.view.ViewGroup parent) {
                android.view.View v = super.getView(pos, conv, parent);
                if (v instanceof android.widget.TextView) {
                    ((android.widget.TextView) v)
                            .setTextColor(android.graphics.Color.WHITE);
                }
                return v;
            }

            @Override
            public android.view.View getDropDownView(int pos, android.view.View conv,
                                                     android.view.ViewGroup parent) {
                android.view.View v = super.getDropDownView(pos, conv, parent);
                if (v instanceof android.widget.TextView) {
                    ((android.widget.TextView) v)
                            .setTextColor(android.graphics.Color.parseColor("#111111"));
                }
                return v;
            }
        };
    }

    /**
     * Parse day-of-week from an ISO date-time string {@code "YYYY-MM-DDTHH:MM"}.
     *
     * @param iso ISO-8601 date-time string
     * @return full English day name ("Monday" … "Sunday"), or {@code ""} on parse failure
     */
    public static String isoDayOfWeek(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            int year  = Integer.parseInt(iso.substring(0, 4));
            int month = Integer.parseInt(iso.substring(5, 7));
            int day   = Integer.parseInt(iso.substring(8, 10));
            Calendar cal = new GregorianCalendar(year, month - 1, day);
            String[] names = {"Sunday","Monday","Tuesday","Wednesday",
                    "Thursday","Friday","Saturday"};
            return names[cal.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Build the UID string for a placed weather marker.
     *
     * @param snapshot location the marker was placed at
     * @return stable UID string
     */
    public static String buildMarkerUid(LocationSnapshot snapshot) {
        if (snapshot.getSource() == LocationSource.SELF_MARKER) return "wx_self";
        return String.format(Locale.US, "wx_centre_%.4f_%.4f",
                snapshot.getLatitude(), snapshot.getLongitude());
    }

    /**
     * Build a compact string listing the altitude tiers present in a profile list.
     * Example: {@code "10/80/120/180m"} for Open-Meteo, {@code "10/760/1500m"} for METAR.
     *
     * @param profiles profile list (may be null or empty)
     * @return tier label string, or {@code ""} if no data is available
     */
    public static String buildAltitudeTierLabel(List<WindProfileModel> profiles) {
        if (profiles == null || profiles.isEmpty()) return "";
        WindProfileModel frame = profiles.get(0);
        if (frame.getAltitudes() == null || frame.getAltitudes().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (WindProfileModel.AltitudeEntry e : frame.getAltitudes()) {
            if (sb.length() > 0) sb.append("/");
            sb.append(e.altitudeMeters);
        }
        sb.append("m");
        return sb.toString();
    }
}
