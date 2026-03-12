package com.atakmap.android.weather.util;

import com.atakmap.android.weather.plugin.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for WMO Weather Interpretation Code → (string res, drawable res).
 *
 * Replaces the four duplicated switch/case blocks that existed in the original
 * WeatherDropDownReceiver (onPostExecute × 3 + seekBarUpdateUI × 1).
 *
 * Usage:
 *   WmoCodeMapper.WmoInfo info = WmoCodeMapper.resolve(weatherCode);
 *   myTextView.setText(info.labelResId);
 *   myImageView.setImageResource(info.drawableResId);
 */
public final class WmoCodeMapper {

    public static final class WmoInfo {
        public final int labelResId;
        public final int drawableResId;

        WmoInfo(int labelResId, int drawableResId) {
            this.labelResId    = labelResId;
            this.drawableResId = drawableResId;
        }
    }

    private static final Map<Integer, WmoInfo> MAPPING = new HashMap<>();

    static {
        // ── Clear / Cloudy ────────────────────────────────────────────────
        MAPPING.put(0,  new WmoInfo(R.string.clear_sky,  R.drawable.wc_00d));
        MAPPING.put(1,  new WmoInfo(R.string.mainly,     R.drawable.wc_01d));
        MAPPING.put(2,  new WmoInfo(R.string.part,       R.drawable.wc_02d));
        MAPPING.put(3,  new WmoInfo(R.string.overcast,   R.drawable.wc_03d));
        // ── Fog ──────────────────────────────────────────────────────────
        MAPPING.put(45, new WmoInfo(R.string.fog1,       R.drawable.wc_45d));
        MAPPING.put(48, new WmoInfo(R.string.fog2,       R.drawable.wc_48d));
        // ── Drizzle ──────────────────────────────────────────────────────
        MAPPING.put(51, new WmoInfo(R.string.driz3,      R.drawable.wc_51d));
        MAPPING.put(53, new WmoInfo(R.string.driz2,      R.drawable.wc_53d));
        MAPPING.put(55, new WmoInfo(R.string.driz1,      R.drawable.wc_55d));
        MAPPING.put(56, new WmoInfo(R.string.frizdriz,   R.drawable.wc_56d));
        MAPPING.put(57, new WmoInfo(R.string.frizdriz1,  R.drawable.wc_57d));
        // ── Rain ─────────────────────────────────────────────────────────
        MAPPING.put(61, new WmoInfo(R.string.rain1,      R.drawable.wc_61d));
        MAPPING.put(63, new WmoInfo(R.string.rain2,      R.drawable.wc_63d));
        MAPPING.put(65, new WmoInfo(R.string.rain3,      R.drawable.wc_65d));
        MAPPING.put(66, new WmoInfo(R.string.freez1,     R.drawable.wc_66d));
        MAPPING.put(67, new WmoInfo(R.string.freez2,     R.drawable.wc_67d));
        // ── Snow ─────────────────────────────────────────────────────────
        MAPPING.put(71, new WmoInfo(R.string.snow1,      R.drawable.wc_71d));
        MAPPING.put(73, new WmoInfo(R.string.snow2,      R.drawable.wc_73d));
        MAPPING.put(75, new WmoInfo(R.string.snow3,      R.drawable.wc_75d));
        MAPPING.put(77, new WmoInfo(R.string.grain,      R.drawable.wc_77d));
        // ── Showers ──────────────────────────────────────────────────────
        MAPPING.put(80, new WmoInfo(R.string.rain6,      R.drawable.wc_80d));
        MAPPING.put(81, new WmoInfo(R.string.rain4,      R.drawable.wc_81d));
        MAPPING.put(82, new WmoInfo(R.string.rain5,      R.drawable.wc_82d));
        MAPPING.put(85, new WmoInfo(R.string.snow4,      R.drawable.wc_85d));
        MAPPING.put(86, new WmoInfo(R.string.snow5,      R.drawable.wc_86d));
        // ── Thunderstorm ─────────────────────────────────────────────────
        MAPPING.put(95, new WmoInfo(R.string.thunder1,   R.drawable.wc_95d));
        MAPPING.put(96, new WmoInfo(R.string.thunder2,   R.drawable.wc_96d));
        MAPPING.put(99, new WmoInfo(R.string.thunder3,   R.drawable.wc_99d));
    }

    private WmoCodeMapper() { /* static utility */ }

    /**
     * Resolve a WMO code to its display resources.
     * Returns the "clear sky" entry as a safe default for unknown codes.
     */
    public static WmoInfo resolve(int wmoCode) {
        WmoInfo info = MAPPING.get(wmoCode);
        return info != null ? info : MAPPING.get(0);
    }
}
