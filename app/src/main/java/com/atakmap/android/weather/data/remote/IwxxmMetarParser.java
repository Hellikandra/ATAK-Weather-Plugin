package com.atakmap.android.weather.data.remote;

import android.util.Xml;

import com.atakmap.coremap.log.Log;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses IWXXM/WMO XML METAR observations from MeteorologicalBulletin XML files.
 * Sprint 19 — S19.3
 *
 * Handles the IWXXM 2021-2 namespaced format where:
 * - Each observation is inside {@code <iwxxm:METAR>}
 * - Station ID is in {@code <aixm:designator>}
 * - Issue time is in {@code <gml:timePosition>}
 * - Weather data is in {@code <iwxxm:MeteorologicalAerodromeObservation>}
 *   with elements like {@code <iwxxm:airTemperature uom="Cel">18</iwxxm:airTemperature>}
 */
public class IwxxmMetarParser {

    private static final String TAG = "IwxxmMetarParser";

    public static class MetarObservation {
        public String stationId;
        public String issueTime;
        public String rawMetar;

        // Wind
        public double windSpeedMs = Double.NaN;
        public double windDirectionDeg = Double.NaN;
        public double windGustMs = Double.NaN;

        // Temperature
        public double temperatureC = Double.NaN;
        public double dewPointC = Double.NaN;

        // Pressure
        public double qnhHPa = Double.NaN;

        // Visibility
        public double visibilityM = Double.NaN;

        // Cloud
        public String cloudDescription;

        public boolean isValid() {
            return stationId != null && !Double.isNaN(temperatureC);
        }

        @Override
        public String toString() {
            return String.format("METAR %s: %.1f\u00b0C wind %.1f@%.0f\u00b0 vis %.0fm QNH %.1f",
                stationId, temperatureC, windSpeedMs, windDirectionDeg, visibilityM, qnhHPa);
        }
    }

    /**
     * Parse an IWXXM METAR XML file. May contain multiple observations
     * wrapped in a {@code <collect:MeteorologicalBulletin>}.
     */
    public static List<MetarObservation> parse(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return parse(fis);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse IWXXM file: " + file.getName(), e);
            return new ArrayList<>();
        }
    }

    public static List<MetarObservation> parse(InputStream is) {
        List<MetarObservation> results = new ArrayList<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(is, "UTF-8");

            MetarObservation current = null;
            boolean inMetar = false;
            boolean inSurfaceWind = false;
            boolean inVisibility = false;

            // Track the local name of the current element for text extraction
            String currentLocalName = null;
            String currentUom = null;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String localName = parser.getName();

                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        currentLocalName = localName;
                        currentUom = parser.getAttributeValue(null, "uom");

                        if ("METAR".equals(localName)) {
                            current = new MetarObservation();
                            inMetar = true;
                        } else if (inMetar) {
                            if ("AerodromeSurfaceWind".equals(localName)) {
                                inSurfaceWind = true;
                            } else if ("AerodromeHorizontalVisibility".equals(localName)) {
                                inVisibility = true;
                            }
                        }
                        break;

                    case XmlPullParser.TEXT:
                        if (current != null && currentLocalName != null) {
                            String text = parser.getText().trim();
                            if (text.isEmpty()) break;

                            switch (currentLocalName) {
                                case "designator":
                                case "locationIndicatorICAO":
                                    // First designator encountered becomes the station ID
                                    if (current.stationId == null) {
                                        current.stationId = text;
                                    }
                                    break;

                                case "timePosition":
                                    if (current.issueTime == null) {
                                        current.issueTime = text;
                                    }
                                    break;

                                case "airTemperature":
                                    current.temperatureC = convertToCelsius(
                                            parseDouble(text), currentUom);
                                    break;

                                case "dewpointTemperature":
                                    current.dewPointC = convertToCelsius(
                                            parseDouble(text), currentUom);
                                    break;

                                case "qnh":
                                    current.qnhHPa = convertToHPa(
                                            parseDouble(text), currentUom);
                                    break;

                                case "meanWindDirection":
                                    if (inSurfaceWind) {
                                        current.windDirectionDeg = parseDouble(text);
                                    }
                                    break;

                                case "meanWindSpeed":
                                    if (inSurfaceWind) {
                                        current.windSpeedMs = convertToMs(
                                                parseDouble(text), currentUom);
                                    }
                                    break;

                                case "windGustSpeed":
                                    if (inSurfaceWind) {
                                        current.windGustMs = convertToMs(
                                                parseDouble(text), currentUom);
                                    }
                                    break;

                                case "prevailingVisibility":
                                    if (inVisibility) {
                                        current.visibilityM = parseDouble(text);
                                    }
                                    break;
                            }
                        }
                        break;

                    case XmlPullParser.END_TAG:
                        if ("METAR".equals(localName)) {
                            if (current != null && current.isValid()) {
                                results.add(current);
                            }
                            current = null;
                            inMetar = false;
                        } else if ("AerodromeSurfaceWind".equals(localName)) {
                            inSurfaceWind = false;
                        } else if ("AerodromeHorizontalVisibility".equals(localName)) {
                            inVisibility = false;
                        }
                        currentLocalName = null;
                        currentUom = null;
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "IWXXM parse error", e);
        }
        return results;
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double convertToMs(double value, String uom) {
        if (Double.isNaN(value) || uom == null) return value;
        // IWXXM commonly uses [kn_i] for knots
        if (uom.contains("kn") || uom.contains("kt") || uom.contains("knot")) {
            return value * 0.514444;
        }
        if (uom.contains("km/h") || uom.contains("kph")) return value / 3.6;
        return value; // assume m/s
    }

    private static double convertToCelsius(double value, String uom) {
        if (Double.isNaN(value) || uom == null) return value;
        if (uom.contains("Cel")) return value; // already Celsius
        if (uom.contains("K") || uom.contains("kelvin")) return value - 273.15;
        if (uom.contains("F") || uom.contains("fahrenheit")) return (value - 32) * 5.0 / 9.0;
        return value;
    }

    private static double convertToHPa(double value, String uom) {
        if (Double.isNaN(value) || uom == null) return value;
        if (uom.contains("hPa")) return value; // already hPa
        if (uom.contains("Pa")) return value / 100.0;
        if (uom.contains("inHg")) return value * 33.8639;
        return value;
    }
}
