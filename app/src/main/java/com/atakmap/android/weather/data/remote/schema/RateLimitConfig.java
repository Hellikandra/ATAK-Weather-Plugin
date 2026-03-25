package com.atakmap.android.weather.data.remote.schema;

/**
 * Rate limit configuration for a weather source API.
 * All fields are nullable to indicate "no limit specified".
 */
public class RateLimitConfig {

    private final Integer requestsPerMinute;
    private final Integer requestsPerHour;
    private final Integer requestsPerDay;
    private final Integer requestsPerMonth;
    private final String note;

    public RateLimitConfig(Integer requestsPerMinute, Integer requestsPerHour,
                           Integer requestsPerDay, Integer requestsPerMonth,
                           String note) {
        this.requestsPerMinute = requestsPerMinute;
        this.requestsPerHour = requestsPerHour;
        this.requestsPerDay = requestsPerDay;
        this.requestsPerMonth = requestsPerMonth;
        this.note = note;
    }

    public Integer getRequestsPerMinute() { return requestsPerMinute; }
    public Integer getRequestsPerHour() { return requestsPerHour; }
    public Integer getRequestsPerDay() { return requestsPerDay; }
    public Integer getRequestsPerMonth() { return requestsPerMonth; }
    public String getNote() { return note; }
}
