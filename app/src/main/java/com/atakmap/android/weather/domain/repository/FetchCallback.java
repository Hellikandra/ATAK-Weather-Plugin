package com.atakmap.android.weather.domain.repository;

/**
 * Result of one asynchronous fetch: exactly one of {@link #onResult} or
 * {@link #onError} is called, once.
 *
 * <p>This lives in the domain rather than beside the remote sources because the
 * capability interfaces below are domain types, and the domain may not depend on
 * `data` — a rule ArchUnit now enforces as a hard assertion. It was previously
 * nested inside `data.remote.IWeatherRemoteSource`.
 *
 * @param <T> the model produced on success
 */
public interface FetchCallback<T> {
    void onResult(T data);
    void onError(String message);
}
