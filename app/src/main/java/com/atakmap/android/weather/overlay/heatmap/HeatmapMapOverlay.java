package com.atakmap.android.weather.overlay.heatmap;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.BaseAdapter;

import com.atakmap.android.hierarchy.HierarchyListFilter;
import com.atakmap.android.hierarchy.HierarchyListItem;
import com.atakmap.android.hierarchy.action.Visibility;
import com.atakmap.android.hierarchy.items.AbstractHierarchyListItem2;
import com.atakmap.android.maps.DeepMapItemQuery;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.MapOverlay;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.util.WeatherConstants;

/**
 * Integrates the weather heatmap overlay into the ATAK Overlay Manager.
 *
 * <h3>Overlay hierarchy</h3>
 * <pre>
 *   weather.overlay  (MapOverlayParent — "Weather")
 *   ├── weather.marker   (WeatherMapOverlay)
 *   ├── weather.wind     (WindMapOverlay)
 *   ├── weather.radar    (RadarMapOverlay)
 *   └── weather.heatmap  (HeatmapMapOverlay)   &lt;- this class
 * </pre>
 *
 * <p>Like the radar overlay, the heatmap is a pixel-rendered layer with no
 * drillable MapItem children. The Overlay Manager shows a visibility toggle
 * that starts/stops the {@link HeatmapOverlayManager}.</p>
 */
public class HeatmapMapOverlay implements MapOverlay {

    public static final String OVERLAY_ID   = WeatherConstants.OVERLAY_HEATMAP_ID;
    public static final String OVERLAY_NAME = WeatherConstants.OVERLAY_HEATMAP_NAME;

    private final MapView               mapView;
    private final Context               pluginContext;
    private final HeatmapOverlayManager heatmapManager;

    public HeatmapMapOverlay(MapView mapView,
                              Context pluginContext,
                              HeatmapOverlayManager heatmapManager) {
        this.mapView        = mapView;
        this.pluginContext  = pluginContext;
        this.heatmapManager = heatmapManager;
    }

    // ── MapOverlay ──────────────────────────────────────────────────────────

    @Override public String getIdentifier() { return OVERLAY_ID; }
    @Override public String getName()       { return OVERLAY_NAME; }

    /** No MapItems — heatmap is pixel-rendered. */
    @Override public MapGroup         getRootGroup()      { return null; }
    @Override public DeepMapItemQuery getQueryFunction()  { return null; }

    @Override
    public HierarchyListItem getListModel(BaseAdapter adapter,
                                          long capabilities,
                                          HierarchyListItem.Sort preferredSort) {
        return new HeatmapHierarchyListItem(adapter);
    }

    // ── HeatmapHierarchyListItem ────────────────────────────────────────────

    /**
     * Minimal Overlay Manager list item for the heatmap overlay.
     * Implements {@link Visibility} for the checkbox toggle.
     */
    private class HeatmapHierarchyListItem extends AbstractHierarchyListItem2
            implements Visibility {

        private final BaseAdapter adapter;

        HeatmapHierarchyListItem(BaseAdapter adapter) {
            this.adapter = adapter;
        }

        @Override public String  getTitle()          { return OVERLAY_NAME; }
        @Override public String  getUID()            { return OVERLAY_ID; }
        @Override public int     getChildCount()      { return 0; }
        @Override public int     getDescendantCount() { return 0; }
        @Override public Object  getUserObject()      { return this; }

        @Override protected void refreshImpl() { }
        @Override public boolean hideIfEmpty() { return false; }
        @Override public boolean isMultiSelectSupported() { return false; }

        @Override
        public Drawable getIconDrawable() {
            return pluginContext.getResources()
                    .getDrawable(R.drawable.ic_launcher, null);
        }

        @Override
        public boolean isVisible() {
            return heatmapManager.isActive();
        }

        @Override
        public boolean setVisible(boolean visible) {
            heatmapManager.setVisibleFromOverlayManager(visible);
            if (adapter != null) adapter.notifyDataSetChanged();
            return true;
        }

        @Override
        public View getExtraView(View reuse, android.view.ViewGroup parent) {
            return null;
        }
    }
}
