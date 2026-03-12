package com.atakmap.android.weather.overlay;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.UriMapDataRef;
import com.atakmap.android.menu.MapMenuButtonWidget;
import com.atakmap.android.menu.MapMenuFactory;
import com.atakmap.android.menu.MapMenuWidget;
import com.atakmap.android.util.IconUtilities;
import com.atakmap.android.weather.WeatherDropDownReceiver;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.WidgetIcon;
import com.atakmap.coremap.log.Log;

/**
 * WeatherMenuFactory — programmatic radial menu for wx_* markers.
 *
 * WHY NOT registerMenu() / setRadialMenu():
 *   Both accept a path string resolved through MapAssets, which uses the ATAK
 *   host app's AssetManager. Plugin APK assets are invisible to it → crash.
 *   registerMapMenuFactory() builds a MapMenuWidget in memory at tap-time.
 *
 * RESPONDS TO:
 *   uid starts with "wx_self"   → full 4-button weather menu
 *   uid starts with "wx_centre" → full 4-button weather menu
 *   uid starts with "wx_wind"   → 2-button wind menu (Details + Remove)
 *   anything else               → returns null (ATAK handles it)
 *
 * BUTTON CLICK MECHANISM:
 *   MapMenuButtonWidget.addOnClickListener(MapWidget.OnClickListener)
 *   Interface: void onMapWidgetClick(MapWidget widget, android.view.MotionEvent event)
 *   On click, fires an AtakBroadcast intent handled by WeatherDropDownReceiver.
 *
 * ICON:
 *   Canvas-drawn coloured circle → encodeBitmap() → UriMapDataRef → WidgetIcon.
 *   WidgetIcon is deprecated since 4.4 but still the concrete accepted type in
 *   AbstractButtonWidget.setIcon(WidgetIcon). The replacement (Icon) is not yet
 *   exposed via a public factory in 5.6 javadoc.
 */
public class WeatherMenuFactory implements MapMenuFactory {

    private static final String TAG = "WeatherMenuFactory";

    private final Context pluginContext;

    public WeatherMenuFactory(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    // ── MapMenuFactory ────────────────────────────────────────────────────────

    @Override
    public MapMenuWidget create(MapItem item) {
        if (!isWeatherItem(item)) return null;

        final String uid     = item.getUID();
        final boolean isWind = uid.startsWith("wx_wind");

        MapMenuWidget menu = new MapMenuWidget();

        if (isWind) {
            // Wind marker: opens the Wind tab, plus Remove
            addButton(menu,
                    "Wind\nDetails",
                    WeatherDropDownReceiver.SHOW_PLUGIN,
                    new String[]{"targetUID", uid, "requestedTab", "wind"},
                    Color.argb(210,  20, 140, 220));  // blue

            addButton(menu,
                    "Remove",
                    WeatherDropDownReceiver.REMOVE_MARKER,
                    new String[]{"targetUID", uid},
                    Color.argb(210, 200,  50,  50));  // red
        } else {
            // Weather marker: full menu
            addButton(menu,
                    "WX\nDetails",
                    WeatherDropDownReceiver.SHOW_PLUGIN,
                    new String[]{"targetUID", uid, "requestedTab", "wthr"},
                    Color.argb(210,   0, 170, 110));  // green

            addButton(menu,
                    "Share\nWX",
                    WeatherDropDownReceiver.SHARE_MARKER,
                    new String[]{"targetUID", uid},
                    Color.argb(210,  80,  80, 200));  // indigo

            addButton(menu,
                    "Remove",
                    WeatherDropDownReceiver.REMOVE_MARKER,
                    new String[]{"targetUID", uid},
                    Color.argb(210, 200,  50,  50));  // red
        }

        return menu;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isWeatherItem(MapItem item) {
        if (item == null) return false;
        final String uid = item.getUID();
        return uid != null
                && (uid.startsWith("wx_self")
                ||  uid.startsWith("wx_centre")
                ||  uid.startsWith("wx_wind"));
    }

    /**
     * Build one radial button.
     *
     * @param menu    Parent MapMenuWidget
     * @param label   Display label (newline allowed)
     * @param action  AtakBroadcast intent action
     * @param extras  Flat key/value pairs: [key, value, key, value, ...]
     * @param color   Button background fill colour
     */
    private void addButton(MapMenuWidget menu,
                           String label,
                           String action,
                           String[] extras,
                           int color) {
        try {
            MapMenuButtonWidget btn = new MapMenuButtonWidget(pluginContext);
            btn.setText(label);

            // ── Icon: encode canvas bitmap → UriMapDataRef → WidgetIcon ──────
            Bitmap bmp = makeCircleIcon(label, color);
            String uri = IconUtilities.encodeBitmap(bmp);
            if (uri != null) {
                UriMapDataRef ref = new UriMapDataRef(uri);
                WidgetIcon icon = new WidgetIcon.Builder()
                        .setImageRef(WidgetIcon.STATE_DEFAULT, ref)
                        .setSize(bmp.getWidth(), bmp.getHeight())
                        .setAnchor(bmp.getWidth() / 2, bmp.getHeight() / 2)
                        .build();
                btn.setIcon(icon);
            }

            // ── Click: fire AtakBroadcast intent ──────────────────────────────
            // MapWidget.OnClickListener: void onMapWidgetClick(MapWidget, MotionEvent)
            final Intent intent = buildIntent(action, extras);
            btn.addOnClickListener(new MapWidget.OnClickListener() {
                @Override
                public void onMapWidgetClick(MapWidget widget, MotionEvent event) {
                    AtakBroadcast.getInstance().sendBroadcast(intent);
                }
            });

            menu.addWidget(btn);

        } catch (Exception e) {
            Log.e(TAG, "addButton failed: action=" + action, e);
        }
    }

    private Intent buildIntent(String action, String[] extras) {
        Intent intent = new Intent(action);
        for (int i = 0; i + 1 < extras.length; i += 2)
            intent.putExtra(extras[i], extras[i + 1]);
        return intent;
    }

    /** Coloured circle with the first letter(s) of the label as visual marker. */
    private Bitmap makeCircleIcon(String label, int color) {
        final int sz = 72;
        Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(color);
        c.drawCircle(sz / 2f, sz / 2f, sz / 2f - 2, circle);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.WHITE);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f);
        c.drawCircle(sz / 2f, sz / 2f, sz / 2f - 3, border);

        // Use first word of label as abbreviation
        String abbr = label.contains("\n") ? label.substring(0, label.indexOf("\n")) : label;
        if (abbr.length() > 3) abbr = abbr.substring(0, 2);

        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Color.WHITE);
        txt.setTextSize(22f);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        float y = sz / 2f - (txt.descent() + txt.ascent()) / 2f;
        c.drawText(abbr, sz / 2f, y, txt);

        return bmp;
    }
}
