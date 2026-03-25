package com.atakmap.android.weather.data.remote;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WeatherParameter;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.util.CoordFormatter;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * IWeatherRemoteSource backed by the ECMWF IFS model via Open-Meteo.
 *
 * ── Differences vs OpenMeteoSource ──────────────────────────────────────────
 *
 *  • Endpoint: https://api.open-meteo.com/v1/ecmwf
 *
 *  • Wind profile uses pressure-level variables instead of fixed height levels.
 *    This gives isobaric winds aligned with aviation weather products (TAFs,
 *    SIGMETs, prognostic charts) rather than anemometer heights:
 *
 *      10 m    (surface)
 *      925 hPa ≈  760 m
 *      850 hPa ≈ 1500 m
 *      700 hPa ≈ 3000 m
 *      500 hPa ≈ 5600 m
 *      300 hPa ≈ 9200 m
 *
 *  • Current weather and daily/hourly forecasts delegate to an internal
 *    OpenMeteoSource so the same parsing logic is reused.
 *
 * ── ECMWF model characteristics ─────────────────────────────────────────────
 *   Resolution: 0.25° (~28 km) | Updates: 4×/day | Range: 10 days
 *   Source: European Centre for Medium-Range Weather Forecasts
 *
 * ── No API key required ───────────────────────────────────────────────────────
 */
public class OpenMeteoECMWFSource implements IWeatherRemoteSource {

    private static final String TAG       = "OpenMeteoECMWFSource";
    public  static final String SOURCE_ID = "open-meteo-ecmwf";

    private static final String BASE_URL = "https://api.open-meteo.com/v1/ecmwf?";

    // Pressure-level wind profile — 6 altitude tiers
    // ISA standard altitudes for each pressure level (metres MSL)
    private static final int[] ALT_M = { 10, 760, 1500, 3000, 5600, 9200 };
    private static final String[] WSPD = {
            "wind_speed_10m",
            "wind_speed_925hPa", "wind_speed_850hPa",
            "wind_speed_700hPa", "wind_speed_500hPa", "wind_speed_300hPa"
    };
    private static final String[] WDIR = {
            "wind_direction_10m",
            "wind_direction_925hPa", "wind_direction_850hPa",
            "wind_direction_700hPa", "wind_direction_500hPa", "wind_direction_300hPa"
    };
    private static final String[] TEMP = {
            "temperature_2m",
            "temperature_925hPa", "temperature_850hPa",
            "temperature_700hPa", "temperature_500hPa", "temperature_300hPa"
    };

    private static final String WIND_PARAMS =
            "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
                    + ",wind_speed_925hPa,wind_direction_925hPa,temperature_925hPa"
                    + ",wind_speed_850hPa,wind_direction_850hPa,temperature_850hPa"
                    + ",wind_speed_700hPa,wind_direction_700hPa,temperature_700hPa"
                    + ",wind_speed_500hPa,wind_direction_500hPa,temperature_500hPa"
                    + ",wind_speed_300hPa,wind_direction_300hPa,temperature_300hPa";

    private static final String SUFFIX = "&wind_speed_unit=ms&timezone=auto";

    /** Delegate for current / daily / hourly — reuses Open-Meteo parsing. */
    private final OpenMeteoSource delegate = new OpenMeteoSource();

    @Override public String getSourceId()    { return SOURCE_ID; }
    @Override public String getDisplayName() { return "Open-Meteo · ECMWF IFS"; }

    @Override
    public List<WeatherParameter> getSupportedParameters() {
        return Collections.unmodifiableList(Arrays.asList(WeatherParameter.values()));
    }

    @Override
    public void setParameterPreferences(WeatherParameterPreferences prefs) {
        delegate.setParameterPreferences(prefs);
    }

    @Override
    public void fetchCurrentWeather(double lat, double lon, FetchCallback<WeatherModel> cb) {
        delegate.fetchCurrentWeather(lat, lon, cb);
    }

    @Override
    public void fetchDailyForecast(double lat, double lon, int days,
                                   FetchCallback<List<DailyForecastModel>> cb) {
        delegate.fetchDailyForecast(lat, lon, days, cb);
    }

    @Override
    public void fetchHourlyForecast(double lat, double lon, int hours,
                                    FetchCallback<List<HourlyEntryModel>> cb) {
        delegate.fetchHourlyForecast(lat, lon, hours, cb);
    }

    /**
     * Fetches ECMWF pressure-level wind data.
     * Returns one WindProfileModel per hourly timestep, each with 6 altitude tiers.
     * WindProfileView and WindChartView consume this identically to OpenMeteoSource.
     */
    @Override
    public void fetchWindProfile(double lat, double lon,
                                 FetchCallback<List<WindProfileModel>> callback) {
        String url = BASE_URL
                + "latitude="   + CoordFormatter.format(lat)
                + "&longitude=" + CoordFormatter.format(lon)
                + WIND_PARAMS + SUFFIX;
        Log.d(TAG, "fetchWindProfile → " + url);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root   = new JSONObject(body);
                    JSONObject hourly = root.getJSONObject("hourly");
                    JSONArray  times  = hourly.getJSONArray("time");
                    int count = times.length();
                    List<WindProfileModel> result = new ArrayList<>(count);

                    for (int i = 0; i < count; i++) {
                        List<WindProfileModel.AltitudeEntry> entries = new ArrayList<>();
                        for (int a = 0; a < ALT_M.length; a++) {
                            double gusts = 0;
                            if (a == 0) {
                                JSONArray g = hourly.optJSONArray("wind_gusts_10m");
                                if (g != null) gusts = g.optDouble(i, 0);
                            }
                            JSONArray sArr = hourly.optJSONArray(WSPD[a]);
                            JSONArray dArr = hourly.optJSONArray(WDIR[a]);
                            JSONArray tArr = hourly.optJSONArray(TEMP[a]);
                            // Pressure levels (925/850/700/500/300 hPa) at indices 1-5
                            Integer pressureHPa = null;
                            String  source      = WindProfileModel.SOURCE_SURFACE;
                            if (a > 0) {
                                source = WindProfileModel.SOURCE_PRESSURE;
                                int[] hpaLevels = { 0, 925, 850, 700, 500, 300 };
                                pressureHPa = hpaLevels[a];
                            }
                            entries.add(new WindProfileModel.AltitudeEntry(
                                    ALT_M[a],
                                    sArr != null ? sArr.optDouble(i, 0) : 0,
                                    dArr != null ? dArr.optDouble(i, 0) : 0,
                                    tArr != null ? tArr.optDouble(i, 0) : 0,
                                    gusts, source, pressureHPa));
                        }
                        result.add(new WindProfileModel(times.getString(i), entries));
                    }
                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "parse error", e);
                    delegate.fetchWindProfile(lat, lon, callback);
                }
            }
            @Override public void onFailure(String err) {
                Log.w(TAG, "failed, falling back: " + err);
                delegate.fetchWindProfile(lat, lon, callback);
            }
        });
    }
}
