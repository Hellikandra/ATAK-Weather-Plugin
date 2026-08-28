package com.atakmap.android.weather.domain.repository;

import com.atakmap.android.weather.domain.model.WindProfileModel;

import java.util.List;

/**
 * A source that can report wind at multiple altitudes.
 *
 * <p><b>Opt-in</b>, like {@link IForecastSource}. The aviation source implements
 * it from real winds-aloft observations; the Open-Meteo sources derive it from
 * model pressure levels.
 */
public interface IWindProfileSource {

    void fetchWindProfile(double lat, double lon,
                          FetchCallback<List<WindProfileModel>> callback);
}
