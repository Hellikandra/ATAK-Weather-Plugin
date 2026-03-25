package com.atakmap.android.weather.presentation.viewmodel;

/**
 * Generic UI state wrapper — represents Loading / Success / Error.
 *
 * <h3>Sprint 2 enhancements (S2.4)</h3>
 * <ul>
 *   <li>{@link ErrorCategory} — categorises errors so the UI can show appropriate
 *       icons and retry affordances.</li>
 *   <li>{@code retryable} flag — indicates whether the error condition is transient
 *       (network timeout) or permanent (bad coordinate).</li>
 *   <li>{@code staleData} — on network failure, the previous successful data can be
 *       attached so the UI can show stale data with an error banner instead of
 *       replacing the entire screen with an error state.</li>
 *   <li>All new fields are backward-compatible — existing callers using
 *       {@code error(String)} get {@code ErrorCategory.UNKNOWN} and {@code retryable = true}.</li>
 * </ul>
 *
 * Java analogue of a Kotlin sealed class.
 */
public final class UiState<T> {

    public enum Status { LOADING, SUCCESS, ERROR }

    /**
     * Error categories for structured error handling.
     * The UI uses this to decide which icon / message template to show.
     */
    public enum ErrorCategory {
        /** No network connectivity (airplane mode, Wi-Fi off). */
        NETWORK,
        /** Server returned an error (HTTP 5xx, API down). */
        SERVER,
        /** Response could not be parsed (unexpected JSON, schema change). */
        PARSE,
        /** Request parameters are invalid (bad coordinates, unsupported region). */
        INVALID_REQUEST,
        /** Request timed out. */
        TIMEOUT,
        /** Catch-all for unclassified errors. */
        UNKNOWN
    }

    private final Status        status;
    private final T             data;
    private final String        errorMessage;
    private final ErrorCategory errorCategory;
    private final boolean       retryable;
    private final T             staleData;

    private UiState(Status status, T data, String errorMessage,
                    ErrorCategory errorCategory, boolean retryable, T staleData) {
        this.status        = status;
        this.data          = data;
        this.errorMessage  = errorMessage;
        this.errorCategory = errorCategory;
        this.retryable     = retryable;
        this.staleData     = staleData;
    }

    // ── Factory methods ──────────────────────────────────────────────────────

    public static <T> UiState<T> loading() {
        return new UiState<>(Status.LOADING, null, null,
                ErrorCategory.UNKNOWN, false, null);
    }

    public static <T> UiState<T> success(T data) {
        return new UiState<>(Status.SUCCESS, data, null,
                ErrorCategory.UNKNOWN, false, null);
    }

    /**
     * Simple error — backward-compatible with Sprint 1 callers.
     * Defaults to {@link ErrorCategory#UNKNOWN} and {@code retryable = true}.
     */
    public static <T> UiState<T> error(String message) {
        return new UiState<>(Status.ERROR, null, message,
                ErrorCategory.UNKNOWN, true, null);
    }

    /**
     * Categorised error with retry hint.
     *
     * @param message       human-readable error description
     * @param category      structured error category
     * @param retryable     true if the operation may succeed on retry
     */
    public static <T> UiState<T> error(String message,
                                        ErrorCategory category,
                                        boolean retryable) {
        return new UiState<>(Status.ERROR, null, message,
                category, retryable, null);
    }

    /**
     * Categorised error with stale data attached.
     * The UI can show the stale data with an error banner overlay instead of
     * replacing the entire view with an error screen.
     *
     * @param message       human-readable error description
     * @param category      structured error category
     * @param retryable     true if the operation may succeed on retry
     * @param staleData     last known good data (may be null)
     */
    public static <T> UiState<T> errorWithStaleData(String message,
                                                     ErrorCategory category,
                                                     boolean retryable,
                                                     T staleData) {
        return new UiState<>(Status.ERROR, null, message,
                category, retryable, staleData);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public Status        getStatus()        { return status; }
    public T             getData()          { return data; }
    public String        getErrorMessage()  { return errorMessage; }
    public ErrorCategory getErrorCategory() { return errorCategory; }
    public boolean       isRetryable()      { return retryable; }

    /**
     * Returns previously successful data that was available before this error.
     * May be null if no prior data exists.
     */
    public T getStaleData() { return staleData; }

    public boolean isLoading() { return status == Status.LOADING; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isError()   { return status == Status.ERROR; }

    /**
     * True if this is an error state but stale data is available for display.
     */
    public boolean hasStaleData() { return isError() && staleData != null; }

    // ── Utility ──────────────────────────────────────────────────────────────

    /**
     * Classify an exception message into an {@link ErrorCategory}.
     * Useful in repository callbacks to auto-categorise errors.
     *
     * @param message error message string (may be null)
     * @return best-guess category
     */
    public static ErrorCategory classifyError(String message) {
        if (message == null) return ErrorCategory.UNKNOWN;
        String lower = message.toLowerCase();

        if (lower.contains("timeout") || lower.contains("timed out")) {
            return ErrorCategory.TIMEOUT;
        }
        if (lower.contains("unable to resolve") || lower.contains("no address")
                || lower.contains("network") || lower.contains("unreachable")
                || lower.contains("connect")) {
            return ErrorCategory.NETWORK;
        }
        if (lower.contains("json") || lower.contains("parse")
                || lower.contains("unexpected")) {
            return ErrorCategory.PARSE;
        }
        if (lower.contains("400") || lower.contains("invalid")
                || lower.contains("bad request")) {
            return ErrorCategory.INVALID_REQUEST;
        }
        if (lower.contains("500") || lower.contains("502")
                || lower.contains("503") || lower.contains("server")) {
            return ErrorCategory.SERVER;
        }
        return ErrorCategory.UNKNOWN;
    }
}
