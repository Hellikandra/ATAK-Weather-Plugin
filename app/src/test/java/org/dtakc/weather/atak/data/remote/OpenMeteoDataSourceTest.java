package org.dtakc.weather.atak.data.remote;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.repository.IWeatherRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Wave 2 — OpenMeteoDataSource with MockWebServer.
 *
 * TC-16a: successful 200 JSON response → onSuccess with valid WeatherModel
 * TC-16b: 500 server error           → onError called
 * TC-16c: network timeout             → onError called
 * TC-16d: request URL contains lat/lon query params
 *
 * Uses OkHttp MockWebServer — no real network calls.
 */
@RunWith(JUnit4.class)
public class OpenMeteoDataSourceTest {

    private MockWebServer server;
    private org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource sut;

    private static final double LAT = 48.85, LON = 2.35;

    /** Minimal Open-Meteo JSON response (current block only). */
    private static final String VALID_JSON = "{\n" +
        "  \"latitude\": 48.85,\n" +
        "  \"longitude\": 2.35,\n" +
        "  \"current\": {\n" +
        "    \"time\": \"2024-07-27T12:00\",\n" +
        "    \"temperature_2m\": 28.5,\n" +
        "    \"relative_humidity_2m\": 55,\n" +
        "    \"wind_speed_10m\": 12.3,\n" +
        "    \"wind_direction_10m\": 270,\n" +
        "    \"weather_code\": 0,\n" +
        "    \"surface_pressure\": 1013.0,\n" +
        "    \"precipitation\": 0.0,\n" +
        "    \"apparent_temperature\": 29.1\n" +
        "  },\n" +
        "  \"daily\": {\n" +
        "    \"time\": [\"2024-07-27\", \"2024-07-28\"],\n" +
        "    \"temperature_2m_max\": [30.0, 28.0],\n" +
        "    \"temperature_2m_min\": [18.0, 17.0],\n" +
        "    \"precipitation_sum\": [0.0, 0.5],\n" +
        "    \"weather_code\": [0, 3]\n" +
        "  },\n" +
        "  \"hourly\": {\n" +
        "    \"time\": [\"2024-07-27T00:00\", \"2024-07-27T01:00\"],\n" +
        "    \"temperature_2m\": [22.0, 21.5],\n" +
        "    \"relative_humidity_2m\": [60, 62],\n" +
        "    \"wind_speed_10m\": [8.0, 7.5],\n" +
        "    \"wind_direction_10m\": [260, 265],\n" +
        "    \"precipitation\": [0.0, 0.1],\n" +
        "    \"surface_pressure\": [1012.0, 1011.5],\n" +
        "    \"weather_code\": [0, 1]\n" +
        "  }\n" +
        "}";

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // Inject the mock base URL — constructor accepts optional baseUrl in refactored version
        sut = new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource(
                server.url("/").toString());
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    // TC-16a: valid JSON → onSuccess with non-null WeatherModel
    @Test
    public void TC16a_validResponse_emitsSuccess() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(VALID_JSON)
                .addHeader("Content-Type", "application/json"));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<WeatherModel> result = new AtomicReference<>();

        sut.getCurrentWeather(LAT, LON, new IWeatherRepository.Callback<WeatherModel>() {
            @Override public void onSuccess(WeatherModel r) { result.set(r); latch.countDown(); }
            @Override public void onError(String m)         { latch.countDown(); }
        });

        assertTrue("Callback not fired within 3s", latch.await(3, TimeUnit.SECONDS));
        assertNotNull("Expected non-null WeatherModel on success", result.get());
        assertEquals(LAT, result.get().getLatitude(), 0.1);
        assertEquals(28.5, result.get().getTemperatureMax(), 0.5);
    }

    // TC-16b: HTTP 500 → onError called
    @Test
    public void TC16b_serverError_emitsError() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> errorMsg = new AtomicReference<>();

        sut.getCurrentWeather(LAT, LON, new IWeatherRepository.Callback<WeatherModel>() {
            @Override public void onSuccess(WeatherModel r) { latch.countDown(); }
            @Override public void onError(String m)         { errorMsg.set(m); latch.countDown(); }
        });

        assertTrue("Error callback not fired within 3s", latch.await(3, TimeUnit.SECONDS));
        assertNotNull("Error message expected", errorMsg.get());
    }

    // TC-16c: connection timeout → onError called
    @Test
    public void TC16c_timeout_emitsError() throws InterruptedException {
        // Throttle body to simulate stall then close connection
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBodyDelay(10, TimeUnit.SECONDS)
                .setBody(VALID_JSON));

        // Use a source with very short timeout for this test
        org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource shortTimeout =
                new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource(
                        server.url("/").toString(), 500 /* ms timeout */);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> errorMsg = new AtomicReference<>();

        shortTimeout.getCurrentWeather(LAT, LON, new IWeatherRepository.Callback<WeatherModel>() {
            @Override public void onSuccess(WeatherModel r) { latch.countDown(); }
            @Override public void onError(String m)         { errorMsg.set(m); latch.countDown(); }
        });

        assertTrue("Timeout callback not fired within 5s", latch.await(5, TimeUnit.SECONDS));
        assertNotNull("Error message expected on timeout", errorMsg.get());
    }

    // TC-16d: request URL includes lat and lon query params
    @Test
    public void TC16d_requestUrl_containsLatLon() throws InterruptedException, Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(VALID_JSON)
                .addHeader("Content-Type", "application/json"));

        CountDownLatch latch = new CountDownLatch(1);
        sut.getCurrentWeather(LAT, LON, new IWeatherRepository.Callback<WeatherModel>() {
            @Override public void onSuccess(WeatherModel r) { latch.countDown(); }
            @Override public void onError(String m)         { latch.countDown(); }
        });

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull("No request recorded", req);
        String path = req.getPath();
        assertNotNull(path);
        assertTrue("URL should contain latitude",  path.contains("latitude="));
        assertTrue("URL should contain longitude", path.contains("longitude="));
    }
}
