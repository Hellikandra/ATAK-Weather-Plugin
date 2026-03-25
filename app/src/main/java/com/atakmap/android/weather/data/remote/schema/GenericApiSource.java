package com.atakmap.android.weather.data.remote.schema;

import android.content.Context;

import com.atakmap.android.weather.data.remote.HttpClient;
import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WeatherParameter;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.util.CoordFormatter;
import com.atakmap.android.weather.util.RateLimiter;
import com.atakmap.android.weather.util.WeatherUnitConverter;
import com.atakmap.coremap.log.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Schema-driven implementation of {@link IWeatherRemoteSource}.
 *
 * <p>This class is the core of Sprint 8.2/8.3: it implements the full
 * {@code IWeatherRemoteSource} contract using a {@link WeatherSourceDefinitionV2}
 * loaded from a JSON definition file. All URL building, response parsing,
 * field mapping, authentication, and rate limiting are driven entirely by the
 * definition -- no provider-specific code is needed.</p>
 *
 * <h3>URL Building</h3>
 * <ol>
 *   <li>Get the {@link EndpointDef} for the requested role ("current", "hourly", "daily", "windProfile").</li>
 *   <li>Start with {@code endpoint.url}.</li>
 *   <li>Replace placeholders in query params ({@code {lat}}, {@code {lon}}, etc.).</li>
 *   <li>Apply auth (query param or header) via {@link AuthProvider}.</li>
 *   <li>Check {@link RateLimiter} before making the request.</li>
 * </ol>
 *
 * <h3>Response Parsing</h3>
 * <ol>
 *   <li>Parse response body as JSONObject.</li>
 *   <li>Use {@link ResponsePathNavigator} to navigate to the {@code responsePath}.</li>
 *   <li>Use {@link FieldMapper} to map API fields to domain model fields.</li>
 *   <li>Apply {@link WeatherCodeTranslator} if {@code weatherCodeMapping} is present.</li>
 *   <li>Build the domain model and return via callback.</li>
 * </ol>
 */
public class GenericApiSource implements IWeatherRemoteSource {

    private static final String TAG = "GenericApiSource";

    private final WeatherSourceDefinitionV2 definition;
    private final Context context;
    private final RateLimiter rateLimiter;
    private final WeatherCodeTranslator codeTranslator;

    // Not used for generic sources, but required by the interface
    @SuppressWarnings("unused")
    private WeatherParameterPreferences paramPrefs;

    /**
     * Create a new GenericApiSource driven by the given definition.
     *
     * @param definition the parsed v2 weather source definition
     * @param context    Android context (for SharedPreferences / auth)
     */
    public GenericApiSource(WeatherSourceDefinitionV2 definition, Context context) {
        this.definition = definition;
        this.context = context;

        // Rate limiter: use requestsPerMinute with 10% headroom, default to 60 rpm
        RateLimitConfig rl = definition.getRateLimit();
        int rpm = 60;
        if (rl != null && rl.getRequestsPerMinute() != null) {
            rpm = (int) (rl.getRequestsPerMinute() * 0.9);
        }
        this.rateLimiter = new RateLimiter(Math.max(1, rpm), 60_000);

        // Weather code translator
        this.codeTranslator = new WeatherCodeTranslator(definition.getWeatherCodeMapping());
    }

    // ── IWeatherRemoteSource ─────────────────────────────────────────────────

    @Override
    public String getSourceId() {
        return definition.getSourceId();
    }

    @Override
    public String getDisplayName() {
        return definition.getDisplayName();
    }

    @Override
    public List<WeatherParameter> getSupportedParameters() {
        // Generic sources report parameters based on definition.
        // For now return the full enum -- a future sprint can refine this
        // by matching definition.getParameters() keys to WeatherParameter entries.
        List<WeatherParameter> params = new ArrayList<>();
        Map<String, List<ParameterDef>> defParams = definition.getParameters();
        if (defParams == null || defParams.isEmpty()) {
            // If no parameters defined, return all
            Collections.addAll(params, WeatherParameter.values());
            return params;
        }

        // Try to match definition parameter keys to WeatherParameter enum apiKeys
        for (WeatherParameter wp : WeatherParameter.values()) {
            if (isParameterSupported(wp, defParams)) {
                params.add(wp);
            }
        }
        // If nothing matched, fall back to full enum
        if (params.isEmpty()) {
            Collections.addAll(params, WeatherParameter.values());
        }
        return params;
    }

    @Override
    public void setParameterPreferences(WeatherParameterPreferences prefs) {
        this.paramPrefs = prefs;
    }

    // ── Fetch: Current Weather ───────────────────────────────────────────────

    @Override
    public void fetchCurrentWeather(double lat, double lon,
                                    FetchCallback<WeatherModel> callback) {
        EndpointDef endpoint = getEndpoint("current");
        if (endpoint == null) {
            callback.onError("No 'current' endpoint defined for " + getSourceId());
            return;
        }

        if (!checkRateLimit(callback)) return;

        String url = buildUrl(endpoint, lat, lon, 0, 0);
        String apiKey = resolveApiKey();
        url = AuthProvider.applyToUrl(url, definition.getAuth(), apiKey);

        Log.d(TAG, "[" + getSourceId() + "] fetchCurrentWeather: " + url);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root = new JSONObject(body);
                    String respPath = endpoint.getResponsePath();
                    JSONObject data;
                    if (respPath != null && !respPath.isEmpty()) {
                        data = ResponsePathNavigator.navigateToObject(root, respPath);
                        if (data == null) {
                            callback.onError("Response path '" + respPath + "' not found");
                            return;
                        }
                    } else {
                        data = root;
                    }

                    WeatherModel model = FieldMapper.buildWeatherModel(
                            data, endpoint.getFieldMapping(), lat, lon, getDisplayName());

                    // Apply weather code translation
                    if (!codeTranslator.isPassThrough()) {
                        int translated = codeTranslator.translate(model.getWeatherCode());
                        if (translated != model.getWeatherCode()) {
                            // Rebuild with translated code
                            model = rebuildWithCode(model, translated);
                        }
                    }

                    callback.onResult(model);
                } catch (Exception e) {
                    Log.e(TAG, "[" + getSourceId() + "] fetchCurrentWeather parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "[" + getSourceId() + "] fetchCurrentWeather failed: " + error);
                callback.onError(error);
            }
        });
    }

    // ── Fetch: Daily Forecast ────────────────────────────────────────────────

    @Override
    public void fetchDailyForecast(double lat, double lon, int days,
                                   FetchCallback<List<DailyForecastModel>> callback) {
        EndpointDef endpoint = getEndpoint("daily");
        if (endpoint == null) {
            callback.onError("No 'daily' endpoint defined for " + getSourceId());
            return;
        }

        if (!checkRateLimit(callback)) return;

        String url = buildUrl(endpoint, lat, lon, 0, days);
        String apiKey = resolveApiKey();
        url = AuthProvider.applyToUrl(url, definition.getAuth(), apiKey);

        Log.d(TAG, "[" + getSourceId() + "] fetchDailyForecast: " + url);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root = new JSONObject(body);
                    String respPath = endpoint.getResponsePath();
                    JSONObject data;
                    if (respPath != null && !respPath.isEmpty()) {
                        data = ResponsePathNavigator.navigateToObject(root, respPath);
                        if (data == null) {
                            callback.onError("Response path '" + respPath + "' not found");
                            return;
                        }
                    } else {
                        data = root;
                    }

                    List<DailyForecastModel> result = FieldMapper.buildDailyForecast(
                            data, endpoint.getFieldMapping(), endpoint.getTimeField());

                    // Limit to requested number of days
                    if (result.size() > days && days > 0) {
                        result = result.subList(0, days);
                    }

                    // Apply weather code translation
                    if (!codeTranslator.isPassThrough()) {
                        result = translateDailyCodes(result);
                    }

                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "[" + getSourceId() + "] fetchDailyForecast parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "[" + getSourceId() + "] fetchDailyForecast failed: " + error);
                callback.onError(error);
            }
        });
    }

    // ── Fetch: Hourly Forecast ───────────────────────────────────────────────

    @Override
    public void fetchHourlyForecast(double lat, double lon, int hours,
                                    FetchCallback<List<HourlyEntryModel>> callback) {
        EndpointDef endpoint = getEndpoint("hourly");
        if (endpoint == null) {
            callback.onError("No 'hourly' endpoint defined for " + getSourceId());
            return;
        }

        if (!checkRateLimit(callback)) return;

        String url = buildUrl(endpoint, lat, lon, hours, 0);
        String apiKey = resolveApiKey();
        url = AuthProvider.applyToUrl(url, definition.getAuth(), apiKey);

        Log.d(TAG, "[" + getSourceId() + "] fetchHourlyForecast: " + url);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root = new JSONObject(body);
                    String respPath = endpoint.getResponsePath();
                    JSONObject data;
                    if (respPath != null && !respPath.isEmpty()) {
                        data = ResponsePathNavigator.navigateToObject(root, respPath);
                        if (data == null) {
                            callback.onError("Response path '" + respPath + "' not found");
                            return;
                        }
                    } else {
                        data = root;
                    }

                    List<HourlyEntryModel> result = FieldMapper.buildHourlyForecast(
                            data, endpoint.getFieldMapping(), endpoint.getTimeField());

                    // Limit to requested hours
                    if (result.size() > hours && hours > 0) {
                        result = result.subList(0, hours);
                    }

                    // Apply weather code translation
                    if (!codeTranslator.isPassThrough()) {
                        result = translateHourlyCodes(result);
                    }

                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "[" + getSourceId() + "] fetchHourlyForecast parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "[" + getSourceId() + "] fetchHourlyForecast failed: " + error);
                callback.onError(error);
            }
        });
    }

    // ── Fetch: Wind Profile ──────────────────────────────────────────────────

    @Override
    public void fetchWindProfile(double lat, double lon,
                                 FetchCallback<List<WindProfileModel>> callback) {
        // Sprint 9 (S9.1): Try pressure-level endpoint first for extended data,
        // then fall back to surface-only. Merge if both available.
        EndpointDef pressureEndpoint = getEndpoint("windProfilePressure");
        EndpointDef surfaceEndpoint  = getEndpoint("windProfile");

        if (pressureEndpoint == null && surfaceEndpoint == null) {
            callback.onError("No 'windProfile' endpoint defined for " + getSourceId());
            return;
        }

        // If no pressure endpoint, use surface-only (original behaviour)
        if (pressureEndpoint == null) {
            fetchWindProfileSurface(lat, lon, surfaceEndpoint, callback);
            return;
        }

        // If no surface endpoint, use pressure-only
        if (surfaceEndpoint == null) {
            fetchWindProfilePressure(lat, lon, pressureEndpoint, callback);
            return;
        }

        // Both available: fetch surface first, then pressure, then merge
        if (!checkRateLimit(callback)) return;

        fetchWindProfileSurface(lat, lon, surfaceEndpoint, new FetchCallback<List<WindProfileModel>>() {
            @Override
            public void onResult(List<WindProfileModel> surfaceResult) {
                // Now fetch pressure data
                fetchWindProfilePressure(lat, lon, pressureEndpoint, new FetchCallback<List<WindProfileModel>>() {
                    @Override
                    public void onResult(List<WindProfileModel> pressureResult) {
                        // Merge surface + pressure
                        List<WindProfileModel> merged = FieldMapper.mergeWindProfiles(
                                surfaceResult, pressureResult);
                        callback.onResult(merged);
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "[" + getSourceId() + "] Pressure wind fetch failed, returning surface-only: " + error);
                        callback.onResult(surfaceResult);
                    }
                });
            }

            @Override
            public void onError(String error) {
                // Surface failed — try pressure-only
                Log.w(TAG, "[" + getSourceId() + "] Surface wind fetch failed, trying pressure-only: " + error);
                fetchWindProfilePressure(lat, lon, pressureEndpoint, callback);
            }
        });
    }

    /**
     * Fetch surface-level wind profile (10m, 80m, 120m, 180m).
     */
    private void fetchWindProfileSurface(double lat, double lon,
                                          EndpointDef endpoint,
                                          FetchCallback<List<WindProfileModel>> callback) {
        if (!checkRateLimit(callback)) return;

        String url = buildUrl(endpoint, lat, lon, 0, 0);
        String apiKey = resolveApiKey();
        url = AuthProvider.applyToUrl(url, definition.getAuth(), apiKey);

        Log.d(TAG, "[" + getSourceId() + "] fetchWindProfileSurface: " + url);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root = new JSONObject(body);
                    String respPath = endpoint.getResponsePath();
                    JSONObject data;
                    if (respPath != null && !respPath.isEmpty()) {
                        data = ResponsePathNavigator.navigateToObject(root, respPath);
                        if (data == null) {
                            callback.onError("Response path '" + respPath + "' not found");
                            return;
                        }
                    } else {
                        data = root;
                    }

                    List<WindProfileModel> result = FieldMapper.buildWindProfile(
                            data,
                            endpoint.getWindAltitudesM(),
                            endpoint.getAltitudeFieldPattern(),
                            null,
                            WindProfileModel.SOURCE_SURFACE);

                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "[" + getSourceId() + "] fetchWindProfileSurface parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "[" + getSourceId() + "] fetchWindProfileSurface failed: " + error);
                callback.onError(error);
            }
        });
    }

    /**
     * Fetch pressure-level wind profile (1000, 925, 850, 700, 500, 300 hPa).
     * Sprint 9 (S9.1).
     */
    private void fetchWindProfilePressure(double lat, double lon,
                                           EndpointDef endpoint,
                                           FetchCallback<List<WindProfileModel>> callback) {
        if (!checkRateLimit(callback)) return;

        String url = buildUrl(endpoint, lat, lon, 0, 0);
        String apiKey = resolveApiKey();
        url = AuthProvider.applyToUrl(url, definition.getAuth(), apiKey);

        Log.d(TAG, "[" + getSourceId() + "] fetchWindProfilePressure: " + url);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root = new JSONObject(body);
                    String respPath = endpoint.getResponsePath();
                    JSONObject data;
                    if (respPath != null && !respPath.isEmpty()) {
                        data = ResponsePathNavigator.navigateToObject(root, respPath);
                        if (data == null) {
                            callback.onError("Response path '" + respPath + "' not found");
                            return;
                        }
                    } else {
                        data = root;
                    }

                    List<WindProfileModel> result = FieldMapper.buildWindProfile(
                            data,
                            endpoint.getWindAltitudesM(),
                            endpoint.getAltitudeFieldPattern(),
                            endpoint.getWindPressureLevelsHPa(),
                            WindProfileModel.SOURCE_PRESSURE);

                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "[" + getSourceId() + "] fetchWindProfilePressure parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "[" + getSourceId() + "] fetchWindProfilePressure failed: " + error);
                callback.onError(error);
            }
        });
    }

    // ── URL Building ─────────────────────────────────────────────────────────

    /**
     * Build the full URL for an endpoint, resolving all placeholders in query params.
     */
    private String buildUrl(EndpointDef endpoint, double lat, double lon,
                            int hours, int days) {
        StringBuilder url = new StringBuilder(endpoint.getUrl());

        Map<String, String> queryParams = endpoint.getQueryParams();
        if (queryParams == null || queryParams.isEmpty()) {
            return url.toString();
        }

        boolean first = !url.toString().contains("?");
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            String value = resolvePlaceholder(entry.getValue(), lat, lon, hours, days);

            if (value == null || value.isEmpty()) continue;

            url.append(first ? '?' : '&');
            url.append(key).append('=').append(value);
            first = false;
        }
        return url.toString();
    }

    /**
     * Resolve placeholder tokens in a query parameter value.
     */
    private String resolvePlaceholder(String template, double lat, double lon,
                                       int hours, int days) {
        if (template == null) return "";

        String result = template;
        result = result.replace("{lat}", CoordFormatter.format(lat));
        result = result.replace("{lon}", CoordFormatter.format(lon));
        result = result.replace("{unit_temp}", WeatherUnitConverter.omTempUnit());
        result = result.replace("{unit_wind}", WeatherUnitConverter.omWindUnit());
        result = result.replace("{unit_precip}", WeatherUnitConverter.omPrecipUnit());
        result = result.replace("{hours}", String.valueOf(hours));
        result = result.replace("{days}", String.valueOf(days));

        // Field list placeholders
        if (result.contains("{current_fields}")) {
            result = result.replace("{current_fields}", buildFieldList("current"));
        }
        if (result.contains("{hourly_fields}")) {
            result = result.replace("{hourly_fields}", buildFieldList("hourly"));
        }
        if (result.contains("{daily_fields}")) {
            result = result.replace("{daily_fields}", buildFieldList("daily"));
        }

        // Date placeholders
        if (result.contains("{start_date}")) {
            result = result.replace("{start_date}", todayIso());
        }
        if (result.contains("{end_date}")) {
            result = result.replace("{end_date}", todayIso());
        }

        return result;
    }

    /**
     * Build a comma-separated list of defaultOn parameter keys for a category.
     */
    private String buildFieldList(String category) {
        Map<String, List<ParameterDef>> params = definition.getParameters();
        if (params == null) return "";

        List<ParameterDef> categoryParams = params.get(category);
        if (categoryParams == null || categoryParams.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ParameterDef p : categoryParams) {
            if (p.isDefaultOn()) {
                if (!first) sb.append(',');
                sb.append(p.getKey());
                first = false;
            }
        }
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Get an endpoint definition by role name.
     */
    private EndpointDef getEndpoint(String role) {
        Map<String, EndpointDef> endpoints = definition.getEndpoints();
        if (endpoints == null) return null;
        return endpoints.get(role);
    }

    /**
     * Resolve the API key for this source.
     */
    private String resolveApiKey() {
        AuthConfig auth = definition.getAuth();
        if (auth == null || !auth.isRequired()) return null;
        return AuthProvider.getApiKey(context, definition.getSourceId(), auth);
    }

    /**
     * Check rate limit before making a request.
     *
     * @return true if the request is allowed, false if rate limited
     */
    private <T> boolean checkRateLimit(FetchCallback<T> callback) {
        if (!rateLimiter.tryAcquire()) {
            Log.w(TAG, "[" + getSourceId() + "] Rate limit exceeded");
            callback.onError("Rate limit exceeded for " + getDisplayName()
                    + ". Please wait before retrying.");
            return false;
        }
        return true;
    }

    /**
     * Rebuild a WeatherModel with a translated weather code.
     */
    private static WeatherModel rebuildWithCode(WeatherModel original, int newCode) {
        return new WeatherModel.Builder(original.getLatitude(), original.getLongitude())
                .locationName(original.getLocationName())
                .temperatureMax(original.getTemperatureMax())
                .temperatureMin(original.getTemperatureMin())
                .apparentTemperature(original.getApparentTemperature())
                .humidity(original.getHumidity())
                .pressure(original.getPressure())
                .visibility(original.getVisibility())
                .windSpeed(original.getWindSpeed())
                .windDirection(original.getWindDirection())
                .precipitationSum(original.getPrecipitationSum())
                .precipitationHours(original.getPrecipitationHours())
                .weatherCode(newCode)
                .requestTimestamp(original.getRequestTimestamp())
                .build();
    }

    /**
     * Translate weather codes in a list of daily forecast models.
     */
    private List<DailyForecastModel> translateDailyCodes(List<DailyForecastModel> originals) {
        List<DailyForecastModel> result = new ArrayList<>(originals.size());
        for (DailyForecastModel d : originals) {
            int translated = codeTranslator.translate(d.getWeatherCode());
            if (translated != d.getWeatherCode()) {
                result.add(new DailyForecastModel.Builder()
                        .date(d.getDate())
                        .dayLabel(d.getDayLabel())
                        .temperatureMax(d.getTemperatureMax())
                        .temperatureMin(d.getTemperatureMin())
                        .weatherCode(translated)
                        .precipitationSum(d.getPrecipitationSum())
                        .precipitationHours(d.getPrecipitationHours())
                        .precipitationProbabilityMax(d.getPrecipitationProbabilityMax())
                        .sunrise(d.getSunrise())
                        .sunset(d.getSunset())
                        .daylightDurationSec(d.getDaylightDurationSec())
                        .build());
            } else {
                result.add(d);
            }
        }
        return result;
    }

    /**
     * Translate weather codes in a list of hourly forecast models.
     */
    private List<HourlyEntryModel> translateHourlyCodes(List<HourlyEntryModel> originals) {
        List<HourlyEntryModel> result = new ArrayList<>(originals.size());
        for (HourlyEntryModel h : originals) {
            int translated = codeTranslator.translate(h.getWeatherCode());
            if (translated != h.getWeatherCode()) {
                result.add(new HourlyEntryModel.Builder()
                        .isoTime(h.getIsoTime())
                        .hour(h.getHour())
                        .temperature(h.getTemperature())
                        .apparentTemperature(h.getApparentTemperature())
                        .humidity(h.getHumidity())
                        .pressure(h.getPressure())
                        .visibility(h.getVisibility())
                        .windSpeed(h.getWindSpeed())
                        .windDirection(h.getWindDirection())
                        .precipitationProbability(h.getPrecipitationProbability())
                        .precipitation(h.getPrecipitation())
                        .weatherCode(translated)
                        .build());
            } else {
                result.add(h);
            }
        }
        return result;
    }

    /**
     * Check if a WeatherParameter is supported by matching its apiKey against
     * the parameter keys in the definition.
     */
    private boolean isParameterSupported(WeatherParameter wp,
                                          Map<String, List<ParameterDef>> defParams) {
        String categoryKey;
        switch (wp.category) {
            case HOURLY:  categoryKey = "hourly"; break;
            case DAILY:   categoryKey = "daily";  break;
            case CURRENT: categoryKey = "current"; break;
            default: return false;
        }
        List<ParameterDef> defs = defParams.get(categoryKey);
        if (defs == null) return false;
        for (ParameterDef pd : defs) {
            if (wp.apiKey.equals(pd.getKey())) return true;
        }
        return false;
    }

    /**
     * Returns today's date in ISO format (yyyy-MM-dd).
     */
    private static String todayIso() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
