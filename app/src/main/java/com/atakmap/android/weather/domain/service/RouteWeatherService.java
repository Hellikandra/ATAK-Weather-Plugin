package com.atakmap.android.weather.domain.service;

import com.atakmap.android.weather.domain.model.WeatherModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * RouteWeatherService — computes weather data along a route defined by waypoints.
 *
 * <h3>Sprint 5 — S5.1 (Domain Layer)</h3>
 *
 * <p>This service provides the data model and computation logic for route-based
 * weather profiles. It does NOT directly integrate with ATAK's Route system —
 * that wiring is done at the presentation layer. The service accepts a list of
 * {@link RouteWaypoint} and produces a {@link RouteWeatherProfile}.</p>
 *
 * <h4>Usage pattern</h4>
 * <ol>
 *   <li>Caller extracts waypoints from an ATAK Route (lat/lon/name/ETA).</li>
 *   <li>For each waypoint, the caller fetches weather and creates a
 *       {@link WaypointWeather} associating the waypoint with its data.</li>
 *   <li>Pass the list to {@link #buildProfile(List)} to get the composite result.</li>
 * </ol>
 *
 * Pure Java, zero Android dependencies.
 */
public final class RouteWeatherService {

    private RouteWeatherService() { /* non-instantiable */ }

    // ═══════════════════════════════════════════════════════════════════════════
    // Data models
    // ═══════════════════════════════════════════════════════════════════════════

    /** A waypoint along the route. */
    public static class RouteWaypoint {
        public final double latitude;
        public final double longitude;
        public final String name;
        /** Estimated time of arrival in ISO format, or empty if unknown. */
        public final String etaIso;
        /** Distance from route start in metres. */
        public final double distanceFromStartM;

        public RouteWaypoint(double lat, double lon, String name,
                             String etaIso, double distanceFromStartM) {
            this.latitude            = lat;
            this.longitude           = lon;
            this.name                = name != null ? name : "";
            this.etaIso              = etaIso != null ? etaIso : "";
            this.distanceFromStartM  = distanceFromStartM;
        }
    }

    /** Weather data associated with a waypoint. */
    public static class WaypointWeather {
        public final RouteWaypoint waypoint;
        public final WeatherModel  weather;
        /** Tactical condition at this waypoint (GREEN/AMBER/RED). */
        public final String        condition;

        public WaypointWeather(RouteWaypoint waypoint, WeatherModel weather) {
            this.waypoint  = waypoint;
            this.weather   = weather;
            this.condition = weather != null ? weather.tacticalCondition() : "GREEN";
        }
    }

    /** Composite route weather profile. */
    public static class RouteWeatherProfile {
        public final List<WaypointWeather> waypoints;
        /** Overall worst condition along the route. */
        public final String worstCondition;
        /** Index of the worst-condition waypoint. */
        public final int worstWaypointIndex;
        /** Temperature range along the route (min, max). */
        public final double minTemp;
        public final double maxTemp;
        /** Maximum wind speed along the route. */
        public final double maxWind;
        /** Total route distance in metres. */
        public final double totalDistanceM;
        /** Number of waypoints with active precipitation. */
        public final int precipWaypointCount;

        RouteWeatherProfile(List<WaypointWeather> waypoints, String worstCondition,
                            int worstIdx, double minTemp, double maxTemp,
                            double maxWind, double totalDistanceM, int precipCount) {
            this.waypoints          = Collections.unmodifiableList(waypoints);
            this.worstCondition     = worstCondition;
            this.worstWaypointIndex = worstIdx;
            this.minTemp            = minTemp;
            this.maxTemp            = maxTemp;
            this.maxWind            = maxWind;
            this.totalDistanceM     = totalDistanceM;
            this.precipWaypointCount = precipCount;
        }

        /**
         * Format a summary for display.
         * @return e.g. "Route: 45.2 km | 12.3–28.5°C | Max wind: 8.2 m/s | 2/5 waypoints with rain"
         */
        public String formatSummary() {
            return String.format(Locale.US,
                    "Route: %.1f km | %.1f–%.1f°C | Max wind: %.1f m/s | %d/%d waypoints with rain | Worst: %s",
                    totalDistanceM / 1000.0, minTemp, maxTemp, maxWind,
                    precipWaypointCount, waypoints.size(), worstCondition);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Profile builder
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build a route weather profile from a list of waypoint-weather pairs.
     *
     * @param waypointWeathers list of waypoints with associated weather data
     * @return RouteWeatherProfile, or null if input is empty
     */
    public static RouteWeatherProfile buildProfile(List<WaypointWeather> waypointWeathers) {
        if (waypointWeathers == null || waypointWeathers.isEmpty()) return null;

        double minTemp = Double.MAX_VALUE;
        double maxTemp = -Double.MAX_VALUE;
        double maxWind = 0;
        int precipCount = 0;
        String worstCond = "GREEN";
        int worstIdx = 0;
        double totalDist = 0;

        for (int i = 0; i < waypointWeathers.size(); i++) {
            WaypointWeather ww = waypointWeathers.get(i);
            if (ww.weather != null) {
                double avgTemp = (ww.weather.getTemperatureMin() + ww.weather.getTemperatureMax()) / 2.0;
                if (avgTemp < minTemp) minTemp = avgTemp;
                if (avgTemp > maxTemp) maxTemp = avgTemp;
                if (ww.weather.getWindSpeed() > maxWind) maxWind = ww.weather.getWindSpeed();
                if (ww.weather.isPrecipitationActive()) precipCount++;
            }

            // Track worst condition
            int condScore = conditionScore(ww.condition);
            if (condScore > conditionScore(worstCond)) {
                worstCond = ww.condition;
                worstIdx = i;
            }

            totalDist = ww.waypoint.distanceFromStartM;
        }

        if (minTemp == Double.MAX_VALUE) minTemp = 0;
        if (maxTemp == -Double.MAX_VALUE) maxTemp = 0;

        return new RouteWeatherProfile(
                new ArrayList<>(waypointWeathers),
                worstCond, worstIdx, minTemp, maxTemp,
                maxWind, totalDist, precipCount);
    }

    private static int conditionScore(String cond) {
        if ("RED".equals(cond)) return 2;
        if ("AMBER".equals(cond)) return 1;
        return 0;
    }
}
