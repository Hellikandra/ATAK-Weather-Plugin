package com.atakmap.android.weather.util;

import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.domain.service.AtmosphericStabilityService;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps WMO weather codes (0-99) to display label resources and drawable icons.
 * Supports both day and night icon variants based on solar elevation.
 *
 * <p>Usage:</p>
 * <pre>
 *   WmoCodeMapper.WmoInfo info = WmoCodeMapper.resolve(weatherCode, lat, lon);
 *   // info.drawableResId → R.drawable.wc_00d or wc_00n depending on time
 * </pre>
 */
public final class WmoCodeMapper {

    public static class WmoInfo {
        public final int labelResId;
        public final int drawableResId;

        WmoInfo(int labelResId, int drawableResId) {
            this.labelResId    = labelResId;
            this.drawableResId = drawableResId;
        }
    }

    /** Day icons. */
    private static final Map<Integer, WmoInfo> DAY_MAP = new HashMap<>();
    /** Night icons. */
    private static final Map<Integer, WmoInfo> NIGHT_MAP = new HashMap<>();

    static {
        // ── Day icons ────────────────────────────────────────────────────
        DAY_MAP.put(0,  new WmoInfo(R.string.clear_sky,  R.drawable.wc_00d));
        DAY_MAP.put(1,  new WmoInfo(R.string.mainly,     R.drawable.wc_01d));
        DAY_MAP.put(2,  new WmoInfo(R.string.part,       R.drawable.wc_02d));
        DAY_MAP.put(3,  new WmoInfo(R.string.overcast,   R.drawable.wc_03d));
        DAY_MAP.put(45, new WmoInfo(R.string.fog1,       R.drawable.wc_45d));
        DAY_MAP.put(48, new WmoInfo(R.string.fog2,       R.drawable.wc_48d));
        DAY_MAP.put(51, new WmoInfo(R.string.driz3,      R.drawable.wc_51d));
        DAY_MAP.put(53, new WmoInfo(R.string.driz2,      R.drawable.wc_53d));
        DAY_MAP.put(55, new WmoInfo(R.string.driz1,      R.drawable.wc_55d));
        DAY_MAP.put(56, new WmoInfo(R.string.frizdriz,   R.drawable.wc_56d));
        DAY_MAP.put(57, new WmoInfo(R.string.frizdriz1,  R.drawable.wc_57d));
        DAY_MAP.put(61, new WmoInfo(R.string.rain1,      R.drawable.wc_61d));
        DAY_MAP.put(63, new WmoInfo(R.string.rain2,      R.drawable.wc_63d));
        DAY_MAP.put(65, new WmoInfo(R.string.rain3,      R.drawable.wc_65d));
        DAY_MAP.put(66, new WmoInfo(R.string.freez1,     R.drawable.wc_66d));
        DAY_MAP.put(67, new WmoInfo(R.string.freez2,     R.drawable.wc_67d));
        DAY_MAP.put(71, new WmoInfo(R.string.snow1,      R.drawable.wc_71d));
        DAY_MAP.put(73, new WmoInfo(R.string.snow2,      R.drawable.wc_73d));
        DAY_MAP.put(75, new WmoInfo(R.string.snow3,      R.drawable.wc_75d));
        DAY_MAP.put(77, new WmoInfo(R.string.grain,      R.drawable.wc_77d));
        DAY_MAP.put(80, new WmoInfo(R.string.rain6,      R.drawable.wc_80d));
        DAY_MAP.put(81, new WmoInfo(R.string.rain4,      R.drawable.wc_81d));
        DAY_MAP.put(82, new WmoInfo(R.string.rain5,      R.drawable.wc_82d));
        DAY_MAP.put(85, new WmoInfo(R.string.snow4,      R.drawable.wc_85d));
        DAY_MAP.put(86, new WmoInfo(R.string.snow5,      R.drawable.wc_86d));
        DAY_MAP.put(95, new WmoInfo(R.string.thunder1,   R.drawable.wc_95d));
        DAY_MAP.put(96, new WmoInfo(R.string.thunder2,   R.drawable.wc_96d));
        DAY_MAP.put(99, new WmoInfo(R.string.thunder3,   R.drawable.wc_99d));

        // ── Night icons ──────────────────────────────────────────────────
        NIGHT_MAP.put(0,  new WmoInfo(R.string.clear_sky,  R.drawable.wc_00n));
        NIGHT_MAP.put(1,  new WmoInfo(R.string.mainly,     R.drawable.wc_01n));
        NIGHT_MAP.put(2,  new WmoInfo(R.string.part,       R.drawable.wc_02n));
        NIGHT_MAP.put(3,  new WmoInfo(R.string.overcast,   R.drawable.wc_03n));
        NIGHT_MAP.put(45, new WmoInfo(R.string.fog1,       R.drawable.wc_45n));
        NIGHT_MAP.put(48, new WmoInfo(R.string.fog2,       R.drawable.wc_48n));
        NIGHT_MAP.put(51, new WmoInfo(R.string.driz3,      R.drawable.wc_51n));
        NIGHT_MAP.put(53, new WmoInfo(R.string.driz2,      R.drawable.wc_53n));
        NIGHT_MAP.put(55, new WmoInfo(R.string.driz1,      R.drawable.wc_55n));
        NIGHT_MAP.put(56, new WmoInfo(R.string.frizdriz,   R.drawable.wc_56n));
        NIGHT_MAP.put(57, new WmoInfo(R.string.frizdriz1,  R.drawable.wc_57n));
        NIGHT_MAP.put(61, new WmoInfo(R.string.rain1,      R.drawable.wc_61n));
        NIGHT_MAP.put(63, new WmoInfo(R.string.rain2,      R.drawable.wc_63n));
        NIGHT_MAP.put(65, new WmoInfo(R.string.rain3,      R.drawable.wc_65n));
        NIGHT_MAP.put(66, new WmoInfo(R.string.freez1,     R.drawable.wc_66n));
        NIGHT_MAP.put(67, new WmoInfo(R.string.freez2,     R.drawable.wc_67n));
        NIGHT_MAP.put(71, new WmoInfo(R.string.snow1,      R.drawable.wc_71n));
        NIGHT_MAP.put(73, new WmoInfo(R.string.snow2,      R.drawable.wc_73n));
        NIGHT_MAP.put(75, new WmoInfo(R.string.snow3,      R.drawable.wc_75n));
        NIGHT_MAP.put(77, new WmoInfo(R.string.grain,      R.drawable.wc_77n));
        NIGHT_MAP.put(80, new WmoInfo(R.string.rain6,      R.drawable.wc_80n));
        NIGHT_MAP.put(81, new WmoInfo(R.string.rain4,      R.drawable.wc_81n));
        NIGHT_MAP.put(82, new WmoInfo(R.string.rain5,      R.drawable.wc_82n));
        NIGHT_MAP.put(85, new WmoInfo(R.string.snow4,      R.drawable.wc_85n));
        NIGHT_MAP.put(86, new WmoInfo(R.string.snow5,      R.drawable.wc_86n));
        NIGHT_MAP.put(95, new WmoInfo(R.string.thunder1,   R.drawable.wc_95n));
        NIGHT_MAP.put(96, new WmoInfo(R.string.thunder2,   R.drawable.wc_96n));
        NIGHT_MAP.put(99, new WmoInfo(R.string.thunder3,   R.drawable.wc_99n));
    }

    private WmoCodeMapper() { /* static utility */ }

    /**
     * Resolve a WMO code to its display resources (always day icon).
     * @deprecated Use {@link #resolve(int, double, double)} for day/night awareness.
     */
    @Deprecated
    public static WmoInfo resolve(int wmoCode) {
        WmoInfo info = DAY_MAP.get(wmoCode);
        return info != null ? info : DAY_MAP.get(0);
    }

    /**
     * Resolve a WMO code with day/night awareness based on solar elevation
     * at the given location and current time.
     *
     * @param wmoCode WMO weather code (0-99)
     * @param lat     latitude of the observation point
     * @param lon     longitude of the observation point
     * @return WmoInfo with the correct day or night icon
     */
    public static WmoInfo resolve(int wmoCode, double lat, double lon) {
        boolean isNight = false;
        try {
            double solarElev = AtmosphericStabilityService.calculateSolarElevation(
                    lat, lon, System.currentTimeMillis());
            isNight = solarElev < -6.0; // Civil twilight threshold
        } catch (Exception ignored) {
            // Fallback to day if solar calculation fails
        }

        Map<Integer, WmoInfo> map = isNight ? NIGHT_MAP : DAY_MAP;
        WmoInfo info = map.get(wmoCode);
        if (info != null) return info;

        // Fallback: try the other map, then default
        WmoInfo fallback = (isNight ? DAY_MAP : NIGHT_MAP).get(wmoCode);
        return fallback != null ? fallback : DAY_MAP.get(0);
    }
}
