package org.dtakc.weather.atak.ui.tab;

import android.content.Context;
import android.view.View;
import com.atakmap.android.maps.MapView;
import org.dtakc.weather.atak.ui.WeatherDependencyContainer;

/**
 * ComparisonTabPresenter — focused tab presenter.
 * TODO: port full logic from WeatherDropDownReceiver.
 */
public final class ComparisonTabPresenter {
    private final Context pluginContext;
    private final View root;
    protected final WeatherDependencyContainer deps;

    public ComparisonTabPresenter(MapView mv, Context ctx, View root, WeatherDependencyContainer deps) {
        this.pluginContext = ctx; this.root = root; this.deps = deps;
    }
    public ComparisonTabPresenter(Context ctx, View root, WeatherDependencyContainer deps) {
        this(null, ctx, root, deps);
    }
    public void init()    {}
    public void dispose() {}
}
