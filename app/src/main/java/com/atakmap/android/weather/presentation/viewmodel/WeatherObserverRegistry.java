package com.atakmap.android.weather.presentation.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages a collection of {@link Observer} registrations made via
 * {@link LiveData#observeForever(Observer)}.
 *
 * <h3>Problem solved</h3>
 * {@code WeatherDropDownReceiver} originally stored 14 typed observer fields
 * ({@code obsCurrentWeather}, {@code obsActiveLocation}, …) as instance variables
 * and manually removed each one in {@code removeObservers()}. This was fragile:
 * adding a new LiveData binding required changes in three places (field declaration,
 * {@code observeForever} call, and {@code removeObserver} call).
 *
 * <h3>Usage</h3>
 * <pre>
 *   private final WeatherObserverRegistry observers = new WeatherObserverRegistry();
 *
 *   // Registration (replaces observeForever + storing the lambda reference):
 *   observers.add(weatherViewModel.getCurrentWeather(), state -> { ... });
 *   observers.add(weatherViewModel.getActiveLocation(), snapshot -> { ... });
 *
 *   // Cleanup (replaces the 14-line removeObservers() method):
 *   observers.removeAll();
 * </pre>
 *
 * <h3>Thread safety</h3>
 * This class is not thread-safe. All calls should be made on the main thread,
 * which is consistent with how ATAK DropDownReceivers operate.
 */
public class WeatherObserverRegistry {

    private static class Registration<T> {
        final LiveData<T>  liveData;
        final Observer<T>  observer;

        Registration(LiveData<T> liveData, Observer<T> observer) {
            this.liveData = liveData;
            this.observer = observer;
        }

        void unregister() {
            liveData.removeObserver(observer);
        }
    }

    private final List<Registration<?>> registrations = new ArrayList<>();

    /**
     * Register {@code observer} on {@code liveData} via
     * {@link LiveData#observeForever(Observer)} and track the pair for later
     * removal via {@link #removeAll()}.
     *
     * @param liveData the LiveData to observe
     * @param observer the observer lambda/instance
     * @param <T>      the LiveData value type
     */
    public <T> void add(LiveData<T> liveData, Observer<T> observer) {
        liveData.observeForever(observer);
        registrations.add(new Registration<>(liveData, observer));
    }

    /**
     * Remove all registered observers from their respective LiveData instances.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    public void removeAll() {
        for (Registration<?> r : registrations) {
            r.unregister();
        }
        registrations.clear();
    }

    /**
     * Returns the number of currently registered observers.
     * Primarily useful for testing.
     */
    public int size() {
        return registrations.size();
    }
}
