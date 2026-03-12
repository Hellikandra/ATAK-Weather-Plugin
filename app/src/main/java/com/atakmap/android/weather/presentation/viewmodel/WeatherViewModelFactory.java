package com.atakmap.android.weather.presentation.viewmodel;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;

/**
 * Factory to construct WeatherViewModel with its repository dependencies.
 * Required because WeatherViewModel has a non-default constructor.
 */
public class WeatherViewModelFactory implements ViewModelProvider.Factory {

    private final IWeatherRepository   weatherRepository;
    private final IGeocodingRepository geocodingRepository;

    public WeatherViewModelFactory(IWeatherRepository weatherRepository,
                                   IGeocodingRepository geocodingRepository) {
        this.weatherRepository   = weatherRepository;
        this.geocodingRepository = geocodingRepository;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(WeatherViewModel.class)) {
            return (T) new WeatherViewModel(weatherRepository, geocodingRepository);
        }
        throw new IllegalArgumentException("Unknown ViewModel: " + modelClass.getName());
    }
}
