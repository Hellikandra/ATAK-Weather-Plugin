package com.atakmap.android.weather.presentation.viewmodel;

/**
 * Generic UI state wrapper — represents Loading / Success / Error.
 *
 * Used as the type parameter for LiveData in WeatherViewModel so the
 * View can react to all three states cleanly.
 *
 * Java analogue of a Kotlin sealed class.
 */
public final class UiState<T> {

    public enum Status { LOADING, SUCCESS, ERROR }

    private final Status status;
    private final T      data;
    private final String errorMessage;

    private UiState(Status status, T data, String errorMessage) {
        this.status       = status;
        this.data         = data;
        this.errorMessage = errorMessage;
    }

    // ── Factory methods ──────────────────────────────────────────────────────

    public static <T> UiState<T> loading() {
        return new UiState<>(Status.LOADING, null, null);
    }

    public static <T> UiState<T> success(T data) {
        return new UiState<>(Status.SUCCESS, data, null);
    }

    public static <T> UiState<T> error(String message) {
        return new UiState<>(Status.ERROR, null, message);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public Status getStatus()       { return status; }
    public T      getData()         { return data; }
    public String getErrorMessage() { return errorMessage; }

    public boolean isLoading() { return status == Status.LOADING; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isError()   { return status == Status.ERROR; }
}
