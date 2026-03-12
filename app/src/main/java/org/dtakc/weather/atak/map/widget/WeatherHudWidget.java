package org.dtakc.weather.atak.map.widget;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.TextWidget;

import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.ui.WeatherDropDownController;

import java.util.Locale;

/**
 * Compact weather HUD widget displayed in the ATAK widget overlay.
 * Layout: [temp] [wind] [location] [flight-cat badge]
 * Tapping the widget opens the weather drop-down.
 */
public final class WeatherHudWidget extends LinearLayoutWidget {

    private final TextWidget tempWidget;
    private final TextWidget windWidget;
    private final TextWidget locationWidget;
    private final TextWidget flightCatWidget;

    public WeatherHudWidget(Context context, MapView mapView) {
        super();
        setLayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        setOrientation(HORIZONTAL);
        // Note: LinearLayoutWidget does not expose setBackgroundColor in ATAK 5.x SDK.
        setPadding(8, 4, 8, 4);

        tempWidget      = addText("--°C");
        windWidget      = addText("--- m/s");
        locationWidget  = addText("Weather");
        flightCatWidget = addText("");
        flightCatWidget.setVisible(false);

        // Tap to open drop-down — use ATAK's OnClickListener interface
        addOnClickListener(new MapWidget.OnClickListener() {
            @Override
            public void onMapWidgetClick(MapWidget widget, android.view.MotionEvent event) {
                Intent intent = new Intent(WeatherDropDownController.SHOW_PLUGIN);
                AtakBroadcast.getInstance().sendBroadcast(intent);
            }
        });
    }

    /** Update widget labels from a WeatherModel. */
    public void bind(WeatherModel w) {
        if (w == null) return;
        tempWidget.setText(String.format(Locale.US, "%.0f°C", w.temperatureMax));
        windWidget.setText(String.format(Locale.US, "%.1f m/s %d°",
                w.windSpeed, (int) w.windDirection));
        if (w.locationName != null && !w.locationName.isEmpty())
            locationWidget.setText(w.locationName);

        if (w.isMetarSource() && !w.flightCategory.isEmpty()) {
            flightCatWidget.setText(w.flightCategory);
            flightCatWidget.setVisible(true);
            flightCatWidget.setColor(flightCatColor(w.flightCategory));
        } else {
            flightCatWidget.setVisible(false);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private TextWidget addText(String initial) {
        TextWidget tw = new TextWidget(initial, 14);
        tw.setColor(0xFFFFFFFF);
        addWidget(tw);
        return tw;
    }

    private static int flightCatColor(String cat) {
        switch (cat) {
            case "VFR":  return 0xFF00AA00;
            case "MVFR": return 0xFF0055FF;
            case "IFR":  return 0xFFCC0000;
            case "LIFR": return 0xFFAA00AA;
            default:     return 0xFF555555;
        }
    }
}
