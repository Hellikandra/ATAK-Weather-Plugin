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
 * ── Sprint 2 changes ──────────────────────────────────────────────────────────
 *
 * 1. setAvailableParameters(List<WeatherParameter>)
 *    ParametersView no longer iterates the full WeatherParameter enum directly.
 *    The Receiver calls setAvailableParameters() with the list returned by
 *    IWeatherRemoteSource.getSupportedParameters() so a different source can
 *    show a different set of checkboxes with no changes here.
 *    Default fallback: full enum (unchanged behaviour).
 *
 * 2. setOnChangeListener(Runnable)
 *    The Receiver registers a callback that re-triggers a weather load when
 *    the user changes any parameter selection. The load only fires after 1 s
 *    of idle (debounced) to avoid hammering the API on rapid taps.
 *
 * 3. Button touch targets
 *    All / None buttons are now 48dp tall × 72dp minimum width per Material
 *    guidelines (previously 28dp — too small to tap reliably).
 *
 * 4. Required-field indication
 *    Parameters in WeatherParameter.MINIMUM_REQUIRED_* are shown with a
 *    lock suffix "🔒" in the display name so the user understands why they
 *    cannot effectively deselect them.
 */
public class ParametersView {

    // ── Listener for parameter changes ────────────────────────────────────────

    public interface OnChangeListener {
        void onParametersChanged();
    }

    private OnChangeListener changeListener;
    private final android.os.Handler debounceHandler = new android.os.Handler();
    private Runnable debounceRunnable;
    private static final long DEBOUNCE_MS = 1000L;

    // ── Per-section state ──────────────────────────────────────────────────
    private SectionState hourlyState;
    private SectionState dailyState;
    private SectionState currentState;

    private final View                        root;
    private final Context                     context;
    private final WeatherParameterPreferences prefs;

    // ── Constructor ────────────────────────────────────────────────────────

    public ParametersView(View root, Context context,
                          WeatherParameterPreferences prefs) {
        this.root    = root;
        this.context = context;
        this.prefs   = prefs;

        // Default: use full enum for Open-Meteo
        buildSections(buildDefaultList());
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Rebuild the three section lists from the given supported parameter list.
     * Call this after IWeatherRemoteSource.getSupportedParameters() returns.
     */
    public void setAvailableParameters(List<WeatherParameter> supported) {
        buildSections(supported);
    }

    /**
     * Register a callback invoked (debounced 1 s) whenever any selection changes.
     * The Receiver uses this to re-trigger a weather load.
     */
    public void setOnChangeListener(OnChangeListener listener) {
        this.changeListener = listener;
    }

    // ── Private ────────────────────────────────────────────────────────────

    private void buildSections(List<WeatherParameter> all) {
        List<WeatherParameter> hourly  = filterBy(all, WeatherParameter.Category.HOURLY);
        List<WeatherParameter> daily   = filterBy(all, WeatherParameter.Category.DAILY);
        List<WeatherParameter> current = filterBy(all, WeatherParameter.Category.CURRENT);

        hourlyState  = new SectionState(root, context, prefs, hourly,
                R.id.params_list_hourly, R.id.params_all_hourly, R.id.params_none_hourly,
                WeatherParameter.MINIMUM_REQUIRED_HOURLY, this::scheduleChangeCallback);

        dailyState   = new SectionState(root, context, prefs, daily,
                R.id.params_list_daily, R.id.params_all_daily, R.id.params_none_daily,
                WeatherParameter.MINIMUM_REQUIRED_DAILY, this::scheduleChangeCallback);

        currentState = new SectionState(root, context, prefs, current,
                R.id.params_list_current, R.id.params_all_current, R.id.params_none_current,
                WeatherParameter.MINIMUM_REQUIRED_CURRENT, this::scheduleChangeCallback);
    }

    private void scheduleChangeCallback() {
        if (changeListener == null) return;
        debounceHandler.removeCallbacks(debounceRunnable != null ? debounceRunnable : () -> {});
        debounceRunnable = () -> changeListener.onParametersChanged();
        debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
    }

    private static List<WeatherParameter> filterBy(List<WeatherParameter> all,
                                                   WeatherParameter.Category cat) {
        List<WeatherParameter> result = new ArrayList<>();
        for (WeatherParameter p : all) if (p.category == cat) result.add(p);
        return result;
    }

    private static List<WeatherParameter> buildDefaultList() {
        return new ArrayList<>(java.util.Arrays.asList(WeatherParameter.values()));
    }

    // ── SectionState ──────────────────────────────────────────────────────────

    private static class SectionState {

        private final ListView                    listView;
        private final List<WeatherParameter>      params;
        private final ParamAdapter                adapter;
        private final WeatherParameterPreferences prefs;

        SectionState(View root, Context context,
                     WeatherParameterPreferences prefs,
                     List<WeatherParameter> params,
                     int listId, int allBtnId, int noneBtnId,
                     java.util.Set<WeatherParameter> required,
                     Runnable onChanged) {
            this.prefs    = prefs;
            this.params   = params;
            this.listView = root.findViewById(listId);
            //if (listView == null) return;

            adapter = new ParamAdapter(context, params, required);
            listView.setAdapter(adapter);

            for (int i = 0; i < params.size(); i++) {
                listView.setItemChecked(i, prefs.isSelected(params.get(i)));
            }

            fixListViewHeight();

            listView.setOnItemClickListener((parent, view, position, id) -> {
                boolean checked = listView.isItemChecked(position);
                prefs.setSelected(params.get(position), checked);
                onChanged.run();
            });

            Button allBtn = root.findViewById(allBtnId);
            if (allBtn != null) allBtn.setOnClickListener(v -> {
                setAll(true);
                onChanged.run();
            });

            Button noneBtn = root.findViewById(noneBtnId);
            if (noneBtn != null) noneBtn.setOnClickListener(v -> {
                setAll(false);
                onChanged.run();
            });
        }

        private void setAll(boolean selected) {
            for (int i = 0; i < params.size(); i++) {
                listView.setItemChecked(i, selected);
                prefs.setSelected(params.get(i), selected);
            }
        }

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
            ViewGroup.LayoutParams lp = listView.getLayoutParams();
            lp.height = totalHeight + (listView.getDividerHeight() * (adapter.getCount() - 1));
            listView.setLayoutParams(lp);
            listView.requestLayout();
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class ParamAdapter extends ArrayAdapter<WeatherParameter> {

        private final java.util.Set<WeatherParameter> required;

        ParamAdapter(Context context, List<WeatherParameter> items,
                     java.util.Set<WeatherParameter> required) {
            super(context, android.R.layout.simple_list_item_multiple_choice, items);
            this.required = required;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            WeatherParameter param = getItem(position);
            if (param != null && view instanceof TextView) {
                TextView tv = (TextView) view;
                // Show lock indicator for minimum-required fields
                String label = required.contains(param)
                        ? param.displayName + " \uD83D\uDD12"
                        : param.displayName;
                tv.setText(label);
                tv.setTextSize(12f);
            }
            return view;
        }
    }
}
