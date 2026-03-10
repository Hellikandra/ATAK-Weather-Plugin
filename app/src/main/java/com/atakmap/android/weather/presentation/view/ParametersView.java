package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.WeatherParameter;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.plugin.R;

import java.util.ArrayList;
import java.util.List;

/**
 * View helper for Tab 4 — Parameter Selector.
 *
 * Renders three multi-select ListViews (Hourly / Daily / Current).
 * Each section has "Select All" and "Select None" buttons.
 *
 * ListView height is set programmatically after the adapter is attached
 * to avoid nested-ScrollView truncation (a known Android limitation).
 *
 * Selections are persisted immediately on each item tap via
 * WeatherParameterPreferences.
 */
public class ParametersView {

    // ── Per-section state ──────────────────────────────────────────────────
    private final SectionState hourlyState;
    private final SectionState dailyState;
    private final SectionState currentState;

    // ── Constructor ────────────────────────────────────────────────────────

    public ParametersView(View root, Context context,
                          WeatherParameterPreferences prefs) {
        hourlyState  = new SectionState(root, context, prefs,
                WeatherParameter.Category.HOURLY,
                R.id.params_list_hourly,
                R.id.params_all_hourly,
                R.id.params_none_hourly);

        dailyState   = new SectionState(root, context, prefs,
                WeatherParameter.Category.DAILY,
                R.id.params_list_daily,
                R.id.params_all_daily,
                R.id.params_none_daily);

        currentState = new SectionState(root, context, prefs,
                WeatherParameter.Category.CURRENT,
                R.id.params_list_current,
                R.id.params_all_current,
                R.id.params_none_current);
    }

    // ── SectionState inner class ───────────────────────────────────────────

    private static class SectionState {

        private final ListView          listView;
        private final List<WeatherParameter> params;
        private final ParamAdapter      adapter;
        private final WeatherParameterPreferences prefs;
        private final WeatherParameter.Category   category;

        SectionState(View root, Context context,
                     WeatherParameterPreferences prefs,
                     WeatherParameter.Category category,
                     int listId, int allBtnId, int noneBtnId) {
            this.prefs    = prefs;
            this.category = category;
            this.listView = root.findViewById(listId);

            // Build ordered param list for this section
            params = new ArrayList<>();
            for (WeatherParameter p : WeatherParameter.values()) {
                if (p.category == category) params.add(p);
            }

            adapter = new ParamAdapter(context, params);
            listView.setAdapter(adapter);

            // Restore persisted selections
            for (int i = 0; i < params.size(); i++) {
                listView.setItemChecked(i, prefs.isSelected(params.get(i)));
            }

            // Fix height so all items are visible inside the parent ScrollView
            fixListViewHeight();

            // Item tap — persist immediately
            listView.setOnItemClickListener((parent, view, position, id) -> {
                boolean checked = listView.isItemChecked(position);
                prefs.setSelected(params.get(position), checked);
            });

            // Select All
            Button allBtn = root.findViewById(allBtnId);
            if (allBtn != null) allBtn.setOnClickListener(v -> setAll(true));

            // Select None
            Button noneBtn = root.findViewById(noneBtnId);
            if (noneBtn != null) noneBtn.setOnClickListener(v -> setAll(false));
        }

        private void setAll(boolean selected) {
            for (int i = 0; i < params.size(); i++) {
                listView.setItemChecked(i, selected);
                prefs.setSelected(params.get(i), selected);
            }
        }

        /**
         * Measure all child views and set a fixed height on the ListView.
         * This avoids the nested ScrollView problem where ListView collapses
         * to show only ~2 items.
         * Must be called after setAdapter().
         */
        private void fixListViewHeight() {
            if (adapter == null || adapter.getCount() == 0) return;

            int totalHeight = 0;
            for (int i = 0; i < adapter.getCount(); i++) {
                View item = adapter.getView(i, null, listView);
                item.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                totalHeight += item.getMeasuredHeight();
            }

            ViewGroup.LayoutParams params = listView.getLayoutParams();
            params.height = totalHeight + (listView.getDividerHeight() * (adapter.getCount() - 1));
            listView.setLayoutParams(params);
            listView.requestLayout();
        }
    }

    // ── Simple ArrayAdapter ────────────────────────────────────────────────

    private static class ParamAdapter extends ArrayAdapter<WeatherParameter> {

        ParamAdapter(Context context, List<WeatherParameter> items) {
            super(context, android.R.layout.simple_list_item_multiple_choice, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // Use system multi-choice item; just set the label text
            View view = super.getView(position, convertView, parent);
            WeatherParameter param = getItem(position);
            if (param != null && view instanceof TextView) {
                ((TextView) view).setText(param.displayName);
                ((TextView) view).setTextSize(12f);
            }
            return view;
        }
    }
}
