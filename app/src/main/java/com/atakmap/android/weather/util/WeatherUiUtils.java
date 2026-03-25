package com.atakmap.android.weather.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

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

    /** Light text color for spinner items (high contrast on dark bg) */
    private static final int TEXT_COLOR = Color.parseColor("#E8EAED");
    /** Dark background for collapsed spinner */
    private static final int BG_COLOR = Color.parseColor("#1E2430");
    /** Dark background for dropdown popup */
    private static final int DROPDOWN_BG = Color.parseColor("#21262d");
    /** Spinner border/outline colour */
    private static final int SPINNER_BORDER = Color.parseColor("#3A4050");

    /**
     * Create an {@link ArrayAdapter} suitable for ATAK plugin spinners.
     *
     * <p><b>IMPORTANT:</b> ATAK plugins MUST NOT use custom {@code R.layout.*}
     * resources in ArrayAdapter — the host app resolves plugin R.layout IDs
     * to wrong resources, causing InflateException crashes. Instead we use
     * {@code android.R.layout.simple_spinner_item} and override colors
     * programmatically.</p>
     *
     * @param context any context (app or plugin — layout is android.R)
     * @param items   the list of items to display
     * @param <T>     item type
     * @return a correctly themed ArrayAdapter
     */
    public static <T> ArrayAdapter<T> makeDarkSpinnerAdapter(Context context,
                                                             List<T> items) {
        ArrayAdapter<T> adapter = new ArrayAdapter<T>(context,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                styleDark(v);
                return v;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                styleDarkDropdown(v);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    /**
     * Apply dark theme to the Spinner widget itself (collapsed view background + arrow tint).
     * Call this right after setAdapter().
     */
    public static void styleSpinnerDark(android.widget.Spinner spinner) {
        if (spinner == null) return;
        // Rounded rect background with border for clear visibility
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(BG_COLOR);
        bg.setStroke(1, SPINNER_BORDER);
        bg.setCornerRadius(4 * spinner.getContext()
                .getResources().getDisplayMetrics().density);
        spinner.setBackground(bg);
        spinner.setPopupBackgroundDrawable(new ColorDrawable(DROPDOWN_BG));
        float dp = spinner.getContext().getResources().getDisplayMetrics().density;
        spinner.setPadding((int)(6*dp), (int)(2*dp), (int)(6*dp), (int)(2*dp));
        spinner.setMinimumHeight((int) (44 * dp));
    }

    private static void styleDark(View v) {
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(TEXT_COLOR);
            ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            ((TextView) v).setSingleLine(true);
            ((TextView) v).setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.setBackgroundColor(BG_COLOR);
            float dp = v.getContext().getResources().getDisplayMetrics().density;
            v.setPadding((int)(10*dp), (int)(6*dp), (int)(8*dp), (int)(6*dp));
        }
    }

    private static void styleDarkDropdown(View v) {
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(TEXT_COLOR);
            ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            v.setBackgroundColor(DROPDOWN_BG);
            float dp = v.getContext().getResources().getDisplayMetrics().density;
            v.setPadding((int)(12*dp), (int)(10*dp), (int)(12*dp), (int)(10*dp));
        }
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
