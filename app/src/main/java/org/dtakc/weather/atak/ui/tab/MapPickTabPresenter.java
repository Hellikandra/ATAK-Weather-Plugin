package org.dtakc.weather.atak.ui.tab;

import android.content.Context;
import android.view.View;
import com.atakmap.android.maps.MapView;
import org.dtakc.weather.atak.map.marker.WeatherMarkerManager;
import org.dtakc.weather.atak.ui.WeatherDependencyContainer;

public final class MapPickTabPresenter {
    private final MapView mv; private final Context ctx; private final View root;
    protected final WeatherDependencyContainer deps;
    private final WeatherMarkerManager markerManager;
    public MapPickTabPresenter(MapView mv, Context ctx, View root,
                               WeatherDependencyContainer deps, WeatherMarkerManager mm) {
        this.mv=mv; this.ctx=ctx; this.root=root; this.deps=deps; this.markerManager=mm;
    }
    public void init()                   {}
    public void shareMarker(String uid)  {}
    public void removeMarker(String uid) {}
    public void dispose()                {}
}
