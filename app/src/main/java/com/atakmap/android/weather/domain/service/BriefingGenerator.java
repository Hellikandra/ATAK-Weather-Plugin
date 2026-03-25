package com.atakmap.android.weather.domain.service;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.util.WeatherUnitConverter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Generates formatted weather briefing documents from all available data.
 *
 * <p>Sprint 12 (S12.3): Produces both plain-text (military briefing style)
 * and HTML versions of the weather briefing. Handles partial data gracefully
 * by skipping sections when data is unavailable.</p>
 */
public final class BriefingGenerator {

    private static final String LINE = "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550"
            + "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550"
            + "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550"
            + "\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550";
    private static final String BULLET = "\u25B8";

    private BriefingGenerator() { /* static utility */ }

    /**
     * Generate a formatted weather briefing from all available data.
     *
     * @param current      current conditions (required)
     * @param daily        daily forecast (may be null)
     * @param hourly       hourly forecast (may be null)
     * @param winds        wind profile data (may be null)
     * @param locationName human-readable location name
     * @param sourceName   data source display name
     * @return BriefingDocument with plain text and HTML versions
     */
    public static BriefingDocument generate(WeatherModel current,
                                             List<DailyForecastModel> daily,
                                             List<HourlyEntryModel> hourly,
                                             List<WindProfileModel> winds,
                                             String locationName,
                                             String sourceName) {
        if (current == null) {
            return new BriefingDocument(
                    "No weather data available for briefing.",
                    "<html><body><p>No weather data available for briefing.</p></body></html>",
                    "Weather Briefing -- No Data",
                    System.currentTimeMillis());
        }

        String nowUtc = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        String locName = locationName != null && !locationName.isEmpty()
                ? locationName : "Unknown Location";
        String srcName = sourceName != null && !sourceName.isEmpty()
                ? sourceName : "Unknown Source";

        String coordStr = String.format(Locale.US, "%.2f%sN, %.2f%sE",
                Math.abs(current.getLatitude()), current.getLatitude() >= 0 ? "" : "S",
                Math.abs(current.getLongitude()), current.getLongitude() >= 0 ? "" : "W");
        // Fix the cardinal direction labels
        coordStr = String.format(Locale.US, "%.2f\u00B0%s, %.2f\u00B0%s",
                Math.abs(current.getLatitude()),
                current.getLatitude() >= 0 ? "N" : "S",
                Math.abs(current.getLongitude()),
                current.getLongitude() >= 0 ? "E" : "W");

        String title = "Weather Briefing -- " + locName + " -- " + nowUtc + " UTC";

        // ── Build plain text ─────────────────────────────────────────────────
        StringBuilder text = new StringBuilder();
        text.append(LINE).append('\n');
        text.append("  WEATHER BRIEFING\n");
        text.append("  Location: ").append(locName)
            .append(" (").append(coordStr).append(")\n");
        text.append("  Source: ").append(srcName)
            .append(" \u2022 Updated: ").append(nowUtc).append(" UTC\n");
        text.append(LINE).append('\n');

        // Section 1: Current Conditions
        text.append('\n').append(BULLET).append(" CURRENT CONDITIONS\n");
        appendCurrentConditions(text, current);

        // Section 2: Tactical Assessment
        text.append('\n').append(BULLET).append(" TACTICAL ASSESSMENT\n");
        appendTacticalAssessment(text, current);

        // Section 3: 24-Hour Forecast (if hourly data available)
        if (hourly != null && !hourly.isEmpty()) {
            text.append('\n').append(BULLET).append(" 24-HOUR FORECAST\n");
            appendHourlyForecast(text, hourly);
        }

        // Section 4: 7-Day Outlook (if daily data available)
        if (daily != null && !daily.isEmpty()) {
            text.append('\n').append(BULLET).append(" 7-DAY OUTLOOK\n");
            appendDailyOutlook(text, daily);
        }

        // Section 5: Wind Profile (if wind data available)
        if (winds != null && !winds.isEmpty()) {
            text.append('\n').append(BULLET).append(" WIND PROFILE\n");
            appendWindProfile(text, winds);
        }

        // Footer
        text.append('\n').append(LINE).append('\n');
        text.append("  Generated by WeatherTool ATAK Plugin\n");
        text.append("  Data: ").append(srcName).append('\n');
        text.append(LINE).append('\n');

        String plainText = text.toString();

        // ── Build HTML ───────────────────────────────────────────────────────
        String html = buildHtml(current, daily, hourly, winds,
                locName, coordStr, srcName, nowUtc);

        return new BriefingDocument(plainText, html, title, System.currentTimeMillis());
    }

    // ── Plain text section builders ──────────────────────────────────────────

    private static void appendCurrentConditions(StringBuilder sb, WeatherModel w) {
        double avgTemp = (w.getTemperatureMin() + w.getTemperatureMax()) / 2.0;
        sb.append("  Temperature:  ").append(WeatherUnitConverter.fmtTemp(avgTemp));
        if (w.getApparentTemperature() != 0) {
            sb.append(" (Feels like ").append(WeatherUnitConverter.fmtTemp(w.getApparentTemperature())).append(')');
        }
        sb.append('\n');

        sb.append("  Wind:         ").append(WeatherUnitConverter.fmtWind(w.getWindSpeed()))
          .append(" / ").append(String.format(Locale.US, "%.0f\u00B0", w.getWindDirection()))
          .append(" (").append(WeatherUnitConverter.degreesToCardinal(w.getWindDirection())).append(")")
          .append(" \u2014 Beaufort ").append(WeatherAnalyticsService.beaufort(w.getWindSpeed()).force)
          .append('\n');

        sb.append("  Visibility:   ").append(WeatherUnitConverter.fmtVisibility(w.getVisibility())).append('\n');
        sb.append("  Pressure:     ").append(WeatherUnitConverter.fmtPressure(w.getPressure())).append('\n');
        sb.append("  Humidity:     ").append(WeatherUnitConverter.fmtHumidity(w.getHumidity())).append('\n');
        sb.append("  Weather:      WMO ").append(w.getWeatherCode()).append('\n');

        if (w.getPrecipitationSum() > 0) {
            sb.append("  Precipitation: ").append(WeatherUnitConverter.fmtPrecip(w.getPrecipitationSum())).append('\n');
        } else {
            sb.append("  Precipitation: None\n");
        }
    }

    private static void appendTacticalAssessment(StringBuilder sb, WeatherModel w) {
        String tac = w.tacticalCondition();
        String fltCat = w.computeFlightCategory();

        String tacDesc;
        switch (tac) {
            case "GREEN": tacDesc = "All parameters favorable"; break;
            case "AMBER": tacDesc = "Marginal conditions, exercise caution"; break;
            case "RED":   tacDesc = "Unfavorable conditions, operations restricted"; break;
            default:      tacDesc = "Unknown"; break;
        }

        sb.append("  Condition:    ").append(tac).append(" \u2014 ").append(tacDesc).append('\n');
        sb.append("  Flight Cat:   ").append(fltCat).append('\n');

        // Limiting factors
        StringBuilder factors = new StringBuilder();
        if (w.getWindSpeed() > 10.0) factors.append("High wind, ");
        if (w.getVisibility() < 5000.0) factors.append("Reduced visibility, ");
        if (w.getWeatherCode() >= 51) factors.append("Active precipitation, ");
        if (w.getWeatherCode() >= 95) factors.append("Thunderstorm activity, ");

        if (factors.length() > 0) {
            factors.setLength(factors.length() - 2); // remove trailing ", "
            sb.append("  Factors:      ").append(factors).append('\n');
        } else {
            sb.append("  Factors:      None limiting\n");
        }
    }

    private static void appendHourlyForecast(StringBuilder sb, List<HourlyEntryModel> hourly) {
        // Show every 3 hours, up to 24 entries
        int count = 0;
        for (int i = 0; i < hourly.size() && count < 8; i += 3) {
            HourlyEntryModel h = hourly.get(i);
            String time = h.getIsoTime();
            if (time.length() >= 16) time = time.substring(11, 16);

            sb.append("  ").append(time).append("  ")
              .append(String.format(Locale.US, "%6s", WeatherUnitConverter.fmtTemp(h.getTemperature())))
              .append("  ").append(String.format(Locale.US, "%8s", WeatherUnitConverter.fmtWind(h.getWindSpeed())))
              .append(" ").append(WeatherUnitConverter.degreesToCardinal(h.getWindDirection()))
              .append("   ").append(String.format(Locale.US, "%6s", WeatherUnitConverter.fmtVisibility(h.getVisibility())))
              .append("  WMO ").append(h.getWeatherCode())
              .append('\n');
            count++;
        }
    }

    private static void appendDailyOutlook(StringBuilder sb, List<DailyForecastModel> daily) {
        for (DailyForecastModel d : daily) {
            String dayLabel = d.getDayLabel();
            if (dayLabel.length() > 5) dayLabel = dayLabel.substring(0, 5);

            sb.append("  ").append(String.format(Locale.US, "%-5s", dayLabel))
              .append("  ").append(WeatherUnitConverter.fmtTempRange(d.getTemperatureMin(), d.getTemperatureMax()))
              .append("  WMO ").append(d.getWeatherCode());

            if (d.getPrecipitationSum() > 0) {
                sb.append("  Precip: ").append(WeatherUnitConverter.fmtPrecip(d.getPrecipitationSum()));
            }
            sb.append('\n');
        }
    }

    private static void appendWindProfile(StringBuilder sb, List<WindProfileModel> winds) {
        sb.append("  Alt(m)  Speed   Dir    Temp\n");

        // Use the first (most recent) wind profile
        WindProfileModel profile = winds.get(0);
        for (WindProfileModel.AltitudeEntry alt : profile.getAltitudes()) {
            sb.append(String.format(Locale.US, "  %5d   %s  %3.0f\u00B0",
                    alt.altitudeMeters,
                    String.format(Locale.US, "%-7s", WeatherUnitConverter.fmtWind(alt.windSpeed)),
                    alt.windDirection));

            if (!Double.isNaN(alt.temperature)) {
                sb.append("   ").append(WeatherUnitConverter.fmtTemp(alt.temperature));
            } else {
                sb.append("    \u2014");
            }
            sb.append('\n');
        }
    }

    // ── HTML builder ─────────────────────────────────────────────────────────

    private static String buildHtml(WeatherModel current,
                                     List<DailyForecastModel> daily,
                                     List<HourlyEntryModel> hourly,
                                     List<WindProfileModel> winds,
                                     String locName, String coordStr,
                                     String srcName, String nowUtc) {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        h.append("<meta charset=\"UTF-8\">\n");
        h.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        h.append("<title>Weather Briefing - ").append(esc(locName)).append("</title>\n");
        h.append("<style>\n");
        h.append("body { background: #1a1a2e; color: #e0e0e0; font-family: 'Courier New', monospace; ");
        h.append("padding: 20px; margin: 0; }\n");
        h.append("h1 { color: #f0c040; text-align: center; border-top: 3px double #f0c040; ");
        h.append("border-bottom: 3px double #f0c040; padding: 10px; }\n");
        h.append("h2 { color: #80c0ff; margin-top: 20px; }\n");
        h.append(".meta { color: #888; font-size: 0.9em; text-align: center; margin-bottom: 20px; }\n");
        h.append("table { border-collapse: collapse; width: 100%; margin: 10px 0; }\n");
        h.append("th, td { text-align: left; padding: 4px 10px; border-bottom: 1px solid #333; }\n");
        h.append("th { color: #80c0ff; }\n");
        h.append(".green { color: #44cc44; } .amber { color: #ccaa00; } .red { color: #cc4444; }\n");
        h.append(".footer { text-align: center; color: #666; margin-top: 30px; ");
        h.append("border-top: 3px double #f0c040; padding-top: 10px; }\n");
        h.append("</style>\n</head>\n<body>\n");

        // Header
        h.append("<h1>WEATHER BRIEFING</h1>\n");
        h.append("<div class=\"meta\">");
        h.append(esc(locName)).append(" (").append(esc(coordStr)).append(")<br>");
        h.append(esc(srcName)).append(" &bull; Updated: ").append(esc(nowUtc)).append(" UTC");
        h.append("</div>\n");

        // Current Conditions
        h.append("<h2>&#9656; CURRENT CONDITIONS</h2>\n");
        h.append("<table>\n");
        double avgTemp = (current.getTemperatureMin() + current.getTemperatureMax()) / 2.0;
        addHtmlRow(h, "Temperature", WeatherUnitConverter.fmtTemp(avgTemp)
                + (current.getApparentTemperature() != 0
                    ? " (Feels like " + WeatherUnitConverter.fmtTemp(current.getApparentTemperature()) + ")"
                    : ""));
        addHtmlRow(h, "Wind", WeatherUnitConverter.fmtWind(current.getWindSpeed())
                + " / " + String.format(Locale.US, "%.0f\u00B0", current.getWindDirection())
                + " (" + WeatherUnitConverter.degreesToCardinal(current.getWindDirection()) + ")");
        addHtmlRow(h, "Visibility", WeatherUnitConverter.fmtVisibility(current.getVisibility()));
        addHtmlRow(h, "Pressure", WeatherUnitConverter.fmtPressure(current.getPressure()));
        addHtmlRow(h, "Humidity", WeatherUnitConverter.fmtHumidity(current.getHumidity()));
        addHtmlRow(h, "Weather", "WMO " + current.getWeatherCode());
        addHtmlRow(h, "Precipitation", current.getPrecipitationSum() > 0
                ? WeatherUnitConverter.fmtPrecip(current.getPrecipitationSum()) : "None");
        h.append("</table>\n");

        // Tactical Assessment
        String tac = current.tacticalCondition();
        String tacClass = tac.toLowerCase(Locale.US);
        h.append("<h2>&#9656; TACTICAL ASSESSMENT</h2>\n");
        h.append("<table>\n");
        addHtmlRow(h, "Condition", "<span class=\"" + tacClass + "\">" + tac + "</span>");
        addHtmlRow(h, "Flight Category", current.computeFlightCategory());
        h.append("</table>\n");

        // Hourly Forecast
        if (hourly != null && !hourly.isEmpty()) {
            h.append("<h2>&#9656; 24-HOUR FORECAST</h2>\n");
            h.append("<table><tr><th>Time</th><th>Temp</th><th>Wind</th>");
            h.append("<th>Vis</th><th>WMO</th></tr>\n");
            int count = 0;
            for (int i = 0; i < hourly.size() && count < 8; i += 3) {
                HourlyEntryModel hr = hourly.get(i);
                String time = hr.getIsoTime();
                if (time.length() >= 16) time = time.substring(11, 16);
                h.append("<tr><td>").append(esc(time)).append("</td>");
                h.append("<td>").append(esc(WeatherUnitConverter.fmtTemp(hr.getTemperature()))).append("</td>");
                h.append("<td>").append(esc(WeatherUnitConverter.fmtWind(hr.getWindSpeed())))
                 .append(" ").append(WeatherUnitConverter.degreesToCardinal(hr.getWindDirection())).append("</td>");
                h.append("<td>").append(esc(WeatherUnitConverter.fmtVisibility(hr.getVisibility()))).append("</td>");
                h.append("<td>").append(hr.getWeatherCode()).append("</td></tr>\n");
                count++;
            }
            h.append("</table>\n");
        }

        // Daily Outlook
        if (daily != null && !daily.isEmpty()) {
            h.append("<h2>&#9656; 7-DAY OUTLOOK</h2>\n");
            h.append("<table><tr><th>Day</th><th>Temp Range</th><th>WMO</th><th>Precip</th></tr>\n");
            for (DailyForecastModel d : daily) {
                h.append("<tr><td>").append(esc(d.getDayLabel())).append("</td>");
                h.append("<td>").append(esc(WeatherUnitConverter.fmtTempRange(d.getTemperatureMin(), d.getTemperatureMax()))).append("</td>");
                h.append("<td>").append(d.getWeatherCode()).append("</td>");
                h.append("<td>").append(d.getPrecipitationSum() > 0
                        ? esc(WeatherUnitConverter.fmtPrecip(d.getPrecipitationSum())) : "0").append("</td></tr>\n");
            }
            h.append("</table>\n");
        }

        // Wind Profile
        if (winds != null && !winds.isEmpty()) {
            h.append("<h2>&#9656; WIND PROFILE</h2>\n");
            h.append("<table><tr><th>Alt(m)</th><th>Speed</th><th>Dir</th><th>Temp</th></tr>\n");
            WindProfileModel profile = winds.get(0);
            for (WindProfileModel.AltitudeEntry alt : profile.getAltitudes()) {
                h.append("<tr><td>").append(alt.altitudeMeters).append("</td>");
                h.append("<td>").append(esc(WeatherUnitConverter.fmtWind(alt.windSpeed))).append("</td>");
                h.append("<td>").append(String.format(Locale.US, "%.0f\u00B0", alt.windDirection)).append("</td>");
                h.append("<td>").append(!Double.isNaN(alt.temperature)
                        ? esc(WeatherUnitConverter.fmtTemp(alt.temperature)) : "\u2014").append("</td></tr>\n");
            }
            h.append("</table>\n");
        }

        // Footer
        h.append("<div class=\"footer\">");
        h.append("Generated by WeatherTool ATAK Plugin<br>");
        h.append("Data: ").append(esc(srcName));
        h.append("</div>\n");

        h.append("</body>\n</html>\n");
        return h.toString();
    }

    private static void addHtmlRow(StringBuilder sb, String label, String value) {
        sb.append("<tr><td><strong>").append(esc(label)).append("</strong></td><td>")
          .append(value).append("</td></tr>\n");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
