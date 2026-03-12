package org.dtakc.weather.atak.ui;

public final class UiState<T> {
    public enum Status { LOADING, SUCCESS, ERROR }
    private final Status status; private final T data; private final String errorMessage;
    private UiState(Status s, T d, String e) { status=s; data=d; errorMessage=e; }
    public static <T> UiState<T> loading()           { return new UiState<>(Status.LOADING, null, null); }
    public static <T> UiState<T> success(T data)     { return new UiState<>(Status.SUCCESS, data, null); }
    public static <T> UiState<T> error(String msg)   { return new UiState<>(Status.ERROR,   null, msg);  }
    public Status getStatus()       { return status; }
    public T      getData()         { return data;   }
    public String getErrorMessage() { return errorMessage; }
    public boolean isLoading() { return status == Status.LOADING; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isError()   { return status == Status.ERROR;   }
}
