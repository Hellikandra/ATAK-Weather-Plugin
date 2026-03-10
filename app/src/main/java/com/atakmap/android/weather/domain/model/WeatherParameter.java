package com.atakmap.android.weather.domain.model;

/**
 * Exhaustive enumeration of all Open-Meteo API parameter variables.
 *
 * Each constant carries:
 *  - apiKey       : the exact string used in the API URL query param
 *  - displayName  : human-readable label shown in the Parameters tab
 *  - category     : which request group it belongs to
 *  - defaultOn    : whether it is selected by default
 */
public enum WeatherParameter {

    // ── HOURLY ───────────────────────────────────────────────────────────────
    HOURLY_TEMPERATURE_2M       ("temperature_2m",               "Temperature (2 m)",                    Category.HOURLY, true),
    HOURLY_RELATIVE_HUMIDITY_2M ("relativehumidity_2m",          "Relative Humidity (2 m)",              Category.HOURLY, true),
    HOURLY_DEWPOINT_2M          ("dewpoint_2m",                  "Dewpoint (2 m)",                       Category.HOURLY, false),
    HOURLY_APPARENT_TEMPERATURE ("apparent_temperature",         "Apparent Temperature",                 Category.HOURLY, true),
    HOURLY_PRECIP_PROBABILITY   ("precipitation_probability",    "Precipitation Probability",            Category.HOURLY, true),
    HOURLY_PRECIPITATION        ("precipitation",               "Precipitation (rain+showers+snow)",    Category.HOURLY, false),
    HOURLY_RAIN                 ("rain",                         "Rain",                                 Category.HOURLY, false),
    HOURLY_SHOWERS              ("showers",                      "Showers",                              Category.HOURLY, false),
    HOURLY_SNOWFALL             ("snowfall",                     "Snowfall",                             Category.HOURLY, false),
    HOURLY_SNOW_DEPTH           ("snow_depth",                   "Snow Depth",                           Category.HOURLY, false),
    HOURLY_WEATHERCODE          ("weathercode",                  "Weather Code",                         Category.HOURLY, true),
    HOURLY_SEA_LEVEL_PRESSURE   ("pressure_msl",                 "Sea Level Pressure",                   Category.HOURLY, false),
    HOURLY_SURFACE_PRESSURE     ("surface_pressure",             "Surface Pressure",                     Category.HOURLY, true),
    HOURLY_CLOUD_COVER          ("cloudcover",                   "Cloud Cover Total",                    Category.HOURLY, false),
    HOURLY_CLOUD_COVER_LOW      ("cloudcover_low",               "Cloud Cover Low",                      Category.HOURLY, false),
    HOURLY_CLOUD_COVER_MID      ("cloudcover_mid",               "Cloud Cover Mid",                      Category.HOURLY, false),
    HOURLY_CLOUD_COVER_HIGH     ("cloudcover_high",              "Cloud Cover High",                     Category.HOURLY, false),
    HOURLY_VISIBILITY           ("visibility",                   "Visibility",                           Category.HOURLY, true),
    HOURLY_EVAPOTRANSPIRATION   ("evapotranspiration",           "Evapotranspiration",                   Category.HOURLY, false),
    HOURLY_ET0                  ("et0_fao_evapotranspiration",   "Reference ET₀",                        Category.HOURLY, false),
    HOURLY_VPD                  ("vapour_pressure_deficit",      "Vapour Pressure Deficit",              Category.HOURLY, false),
    HOURLY_WINDSPEED_10M        ("windspeed_10m",                "Wind Speed (10 m)",                    Category.HOURLY, true),
    HOURLY_WINDSPEED_80M        ("windspeed_80m",                "Wind Speed (80 m)",                    Category.HOURLY, false),
    HOURLY_WINDSPEED_120M       ("windspeed_120m",               "Wind Speed (120 m)",                   Category.HOURLY, false),
    HOURLY_WINDSPEED_180M       ("windspeed_180m",               "Wind Speed (180 m)",                   Category.HOURLY, false),
    HOURLY_WINDDIRECTION_10M    ("winddirection_10m",            "Wind Direction (10 m)",                Category.HOURLY, true),
    HOURLY_WINDDIRECTION_80M    ("winddirection_80m",            "Wind Direction (80 m)",                Category.HOURLY, false),
    HOURLY_WINDDIRECTION_120M   ("winddirection_120m",           "Wind Direction (120 m)",               Category.HOURLY, false),
    HOURLY_WINDDIRECTION_180M   ("winddirection_180m",           "Wind Direction (180 m)",               Category.HOURLY, false),
    HOURLY_WINDGUSTS_10M        ("windgusts_10m",                "Wind Gusts (10 m)",                    Category.HOURLY, false),
    HOURLY_TEMP_80M             ("temperature_80m",              "Temperature (80 m)",                   Category.HOURLY, false),
    HOURLY_TEMP_120M            ("temperature_120m",             "Temperature (120 m)",                  Category.HOURLY, false),
    HOURLY_TEMP_180M            ("temperature_180m",             "Temperature (180 m)",                  Category.HOURLY, false),
    HOURLY_SOIL_TEMP_0CM        ("soil_temperature_0cm",         "Soil Temperature (0 cm)",              Category.HOURLY, false),
    HOURLY_SOIL_TEMP_6CM        ("soil_temperature_6cm",         "Soil Temperature (6 cm)",              Category.HOURLY, false),
    HOURLY_SOIL_TEMP_18CM       ("soil_temperature_18cm",        "Soil Temperature (18 cm)",             Category.HOURLY, false),
    HOURLY_SOIL_TEMP_54CM       ("soil_temperature_54cm",        "Soil Temperature (54 cm)",             Category.HOURLY, false),
    HOURLY_SOIL_MOISTURE_0_1    ("soil_moisture_0_1cm",          "Soil Moisture (0-1 cm)",               Category.HOURLY, false),
    HOURLY_SOIL_MOISTURE_1_3    ("soil_moisture_1_3cm",          "Soil Moisture (1-3 cm)",               Category.HOURLY, false),
    HOURLY_SOIL_MOISTURE_3_9    ("soil_moisture_3_9cm",          "Soil Moisture (3-9 cm)",               Category.HOURLY, false),
    HOURLY_SOIL_MOISTURE_9_27   ("soil_moisture_9_27cm",         "Soil Moisture (9-27 cm)",              Category.HOURLY, false),
    HOURLY_SOIL_MOISTURE_27_81  ("soil_moisture_27_81cm",        "Soil Moisture (27-81 cm)",             Category.HOURLY, false),

    // ── DAILY ────────────────────────────────────────────────────────────────
    DAILY_WEATHERCODE           ("weathercode",                  "Weather Code",                         Category.DAILY, true),
    DAILY_TEMP_MAX              ("temperature_2m_max",           "Maximum Temperature (2 m)",            Category.DAILY, true),
    DAILY_TEMP_MIN              ("temperature_2m_min",           "Minimum Temperature (2 m)",            Category.DAILY, true),
    DAILY_APPARENT_TEMP_MAX     ("apparent_temperature_max",     "Maximum Apparent Temperature (2 m)",   Category.DAILY, false),
    DAILY_APPARENT_TEMP_MIN     ("apparent_temperature_min",     "Minimum Apparent Temperature (2 m)",   Category.DAILY, false),
    DAILY_SUNRISE               ("sunrise",                      "Sunrise",                              Category.DAILY, false),
    DAILY_SUNSET                ("sunset",                       "Sunset",                               Category.DAILY, false),
    DAILY_DAYLIGHT_DURATION     ("daylight_duration",            "Daylight Duration",                    Category.DAILY, false),
    DAILY_SUNSHINE_DURATION     ("sunshine_duration",            "Sunshine Duration",                    Category.DAILY, false),
    DAILY_UV_INDEX              ("uv_index_max",                 "UV Index",                             Category.DAILY, false),
    DAILY_UV_INDEX_CLEAR_SKY    ("uv_index_clear_sky_max",       "UV Index Clear Sky",                   Category.DAILY, false),
    DAILY_RAIN_SUM              ("rain_sum",                     "Rain Sum",                             Category.DAILY, false),
    DAILY_SHOWERS_SUM           ("showers_sum",                  "Showers Sum",                          Category.DAILY, false),
    DAILY_SNOWFALL_SUM          ("snowfall_sum",                 "Snowfall Sum",                         Category.DAILY, false),
    DAILY_PRECIPITATION_SUM     ("precipitation_sum",            "Precipitation Sum",                    Category.DAILY, true),
    DAILY_PRECIPITATION_HOURS   ("precipitation_hours",          "Precipitation Hours",                  Category.DAILY, true),
    DAILY_PRECIP_PROB_MAX       ("precipitation_probability_max","Precipitation Probability Max",        Category.DAILY, true),
    DAILY_WINDSPEED_10M_MAX     ("windspeed_10m_max",            "Maximum Wind Speed (10 m)",            Category.DAILY, false),
    DAILY_WINDGUSTS_10M_MAX     ("windgusts_10m_max",            "Maximum Wind Gusts (10 m)",            Category.DAILY, false),
    DAILY_WINDDIRECTION_10M_DOM ("winddirection_10m_dominant",   "Dominant Wind Direction (10 m)",       Category.DAILY, false),
    DAILY_SHORTWAVE_RADIATION   ("shortwave_radiation_sum",      "Shortwave Radiation Sum",              Category.DAILY, false),
    DAILY_ET0                   ("et0_fao_evapotranspiration",   "Reference ET₀",                        Category.DAILY, false),

    // ── CURRENT ──────────────────────────────────────────────────────────────
    CURRENT_TEMPERATURE_2M      ("temperature_2m",               "Temperature (2 m)",                    Category.CURRENT, true),
    CURRENT_RELATIVE_HUMIDITY   ("relativehumidity_2m",          "Relative Humidity (2 m)",              Category.CURRENT, true),
    CURRENT_APPARENT_TEMP       ("apparent_temperature",         "Apparent Temperature",                 Category.CURRENT, true),
    CURRENT_IS_DAY              ("is_day",                       "Is Day or Night",                      Category.CURRENT, false),
    CURRENT_PRECIPITATION       ("precipitation",               "Precipitation",                        Category.CURRENT, false),
    CURRENT_RAIN                ("rain",                         "Rain",                                 Category.CURRENT, false),
    CURRENT_SHOWERS             ("showers",                      "Showers",                              Category.CURRENT, false),
    CURRENT_SNOWFALL            ("snowfall",                     "Snowfall",                             Category.CURRENT, false),
    CURRENT_WEATHERCODE         ("weathercode",                  "Weather Code",                         Category.CURRENT, true),
    CURRENT_CLOUD_COVER         ("cloudcover",                   "Cloud Cover Total",                    Category.CURRENT, false),
    CURRENT_SEA_LEVEL_PRESSURE  ("pressure_msl",                 "Sea Level Pressure",                   Category.CURRENT, false),
    CURRENT_SURFACE_PRESSURE    ("surface_pressure",             "Surface Pressure",                     Category.CURRENT, true),
    CURRENT_WINDSPEED_10M       ("windspeed_10m",                "Wind Speed (10 m)",                    Category.CURRENT, true),
    CURRENT_WINDDIRECTION_10M   ("winddirection_10m",            "Wind Direction (10 m)",                Category.CURRENT, true),
    CURRENT_WINDGUSTS_10M       ("windgusts_10m",                "Wind Gusts (10 m)",                    Category.CURRENT, false);

    // ── Metadata ──────────────────────────────────────────────────────────────
    public enum Category { HOURLY, DAILY, CURRENT }

    public final String   apiKey;
    public final String   displayName;
    public final Category category;
    public final boolean  defaultOn;

    WeatherParameter(String apiKey, String displayName,
                     Category category, boolean defaultOn) {
        this.apiKey      = apiKey;
        this.displayName = displayName;
        this.category    = category;
        this.defaultOn   = defaultOn;
    }
}
