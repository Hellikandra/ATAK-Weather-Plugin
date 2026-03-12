package org.dtakc.weather.atak.ui.tab;

import android.content.Context;
import android.view.View;
import com.atakmap.android.maps.MapView;
import org.dtakc.weather.atak.map.marker.WindMarkerManager;
import org.dtakc.weather.atak.ui.WeatherDependencyContainer;

public final class WindTabPresenter {
    private final MapView mv; private final Context ctx; private final View root;
    protected final WeatherDependencyContainer deps;
    private final WindMarkerManager windMarkerManager;
    public WindTabPresenter(MapView mv, Context ctx, View root,
                            WeatherDependencyContainer deps, WindMarkerManager wmm) {
        this.mv=mv; this.ctx=ctx; this.root=root; this.deps=deps; this.windMarkerManager=wmm;
    }
    public void init()           {}
    public void clearWindShapes(){}
    public void dispose()        {}
}
