package com.atakmap.android.weather.presentation.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;

import java.util.List;

/**
 * ViewModel for Tab 3 (Wind Profile / SandBox).
 */
public class WindProfileViewModel extends ViewModel {

    private final MutableLiveData<UiState<List<WindProfileModel>>> windProfile =
            new MutableLiveData<>();

    private final IWeatherRepository weatherRepository;

    public WindProfileViewModel(IWeatherRepository weatherRepository) {
        this.weatherRepository = weatherRepository;
    }

    public void loadWindProfile(double latitude, double longitude) {
        windProfile.setValue(UiState.loading());

        weatherRepository.getWindProfile(latitude, longitude,
                new IWeatherRepository.Callback<List<WindProfileModel>>() {
                    @Override
                    public void onSuccess(List<WindProfileModel> result) {
                        windProfile.setValue(UiState.success(result));
                    }
                    @Override
                    public void onError(String message) {
                        windProfile.setValue(UiState.error(message));
                    }
                });
    }

    public LiveData<UiState<List<WindProfileModel>>> getWindProfile() {
        return windProfile;
    }
}
