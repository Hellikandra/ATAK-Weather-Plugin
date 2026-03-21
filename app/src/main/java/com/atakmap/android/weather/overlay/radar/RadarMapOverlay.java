package com.atakmap.android.weather.overlay.radar;

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

/**
 * RadarMapOverlay — integrates the precipitation radar into the ATAK Overlay Manager.
 *
 * <h3>Overlay hierarchy</h3>
 * <pre>
 *   weather.overlay  (MapOverlayParent — "Weather")
 *   ├── weather.marker   (WeatherMapOverlay)
 *   ├── weather.wind     (WindMapOverlay)
 *   └── weather.radar    (RadarMapOverlay)   ← this class
 * </pre>
 *
 * <h3>Why not DefaultMapGroupOverlay?</h3>
 * The radar is a pixel-rendered tile layer, not a collection of {@code MapItem}s.
 * {@link #getRootGroup()} returns {@code null} — the Overlay Manager shows
 * no drillable item list, just a visibility toggle.
 *
 * <h3>Overlay Manager toggle</h3>
 * {@link #getListModel} returns a {@link RadarHierarchyListItem} whose
 * {@link RadarHierarchyListItem#setVisible(boolean)} calls
 * {@link RadarOverlayManager#start()} / {@link RadarOverlayManager#stop()}.
 *
 * <h3>Registration — WeatherMapComponent.onCreate</h3>
 * <pre>
 *   radarMapOverlay = new RadarMapOverlay(view, context, radarManager);
 *   view.getMapOverlayManager().addOverlay(overlayParent, radarMapOverlay);
 * </pre>
 */
public class RadarMapOverlay implements MapOverlay {

    public static final String OVERLAY_ID   = "weather.radar";
    public static final String OVERLAY_NAME = "WX Radar";

    private final MapView             mapView;
    private final Context             pluginContext;
    private final RadarOverlayManager radarManager;

    public RadarMapOverlay(MapView mapView,
                           Context pluginContext,
                           RadarOverlayManager radarManager) {
        this.mapView       = mapView;
        this.pluginContext = pluginContext;
        this.radarManager  = radarManager;
    }

    // ── MapOverlay ────────────────────────────────────────────────────────────

    @Override public String getIdentifier() { return OVERLAY_ID; }
    @Override public String getName()       { return OVERLAY_NAME; }

    /** No MapItems — tiles are pixel-rendered, not selectable map items. */
    @Override public MapGroup         getRootGroup()      { return null; }
    @Override public DeepMapItemQuery getQueryFunction()  { return null; }

    @Override
    public HierarchyListItem getListModel(BaseAdapter adapter,
                                          long capabilities,
                                          HierarchyListItem.Sort preferredSort) {
        return new RadarHierarchyListItem(adapter);
    }

    // ── RadarHierarchyListItem ────────────────────────────────────────────────

    /**
     * Minimal Overlay Manager list item for the radar overlay.
     * The visibility toggle starts/stops the {@link RadarOverlayManager}.
     *
     * <p>Implements {@link Visibility} so that
     * {@code getAction(Visibility.class)} returns {@code this}, which is how
     * ATAK's {@code HierarchyListAdapter} discovers the visibility checkbox.
     * Without this interface the item would be invisible in the Overlay Manager
     * because {@code AbstractHierarchyListItem2} does <em>not</em> implement
     * Visibility by default.</p>
     */
    private class RadarHierarchyListItem extends AbstractHierarchyListItem2
            implements Visibility {

        private final BaseAdapter adapter;

        RadarHierarchyListItem(BaseAdapter adapter) {
            this.adapter = adapter;
        }

        // ── Required by HierarchyListItem ─────────────────────────────────────

        @Override public String  getTitle()    { return OVERLAY_NAME; }
        @Override public String  getUID()      { return OVERLAY_ID; }
        @Override public int     getChildCount()      { return 0; }
        @Override public int     getDescendantCount() { return 0; }

        /**
         * Returns the user object associated with this node.
         * Required by {@link HierarchyListItem#getUserObject()}.
         * We return {@code this} since the list item IS the backing object.
         */
        @Override
        public Object getUserObject() { return this; }

        // ── Required by AbstractHierarchyListItem2 ────────────────────────────

        @Override
        protected void refreshImpl() { /* no children to refresh */ }

        @Override
        public boolean hideIfEmpty() { return false; }

        // ── Optional overrides ────────────────────────────────────────────────

        @Override
        public boolean isMultiSelectSupported() { return false; }

        @Override
        public Drawable getIconDrawable() {
            return pluginContext.getResources()
                    .getDrawable(R.drawable.ic_launcher, null);
        }

        /**
         * Visibility = radar is currently active (tiles being displayed).
         * Uses {@link RadarOverlayManager#isActive()} which was added in the
         * refactored version of that class.
         */
        @Override
        public boolean isVisible() {
            return radarManager.isActive();
        }

        /** Checkbox toggle from the Overlay Manager. */
        @Override
        public boolean setVisible(boolean visible) {
            if (visible) {
                radarManager.start();
            } else {
                radarManager.stop();
            }
            if (adapter != null) adapter.notifyDataSetChanged();
            return true;
        }

        @Override
        public View getExtraView(View reuse, android.view.ViewGroup parent) {
            return null;
        }
    }
}
