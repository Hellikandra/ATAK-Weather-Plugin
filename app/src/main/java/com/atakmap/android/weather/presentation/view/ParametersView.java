package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.WeatherParameter;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.plugin.R;

import java.util.ArrayList;
import java.util.List;

/**
 * View helper for Tab 4 — Parameter Selector.
 *
 * Uses CheckBox rows inside a LinearLayout container so they size correctly
 * inside a NestedScrollView.  ListView inside NestedScrollView requires a
 * manual height fix that calls getMeasuredHeight() before layout, which
 * returns 0 — making all sections appear empty.
 */
public class ParametersView {

    public interface OnChangeListener {
        void onParametersChanged();
    }

    private OnChangeListener changeListener;
    private final android.os.Handler debounceHandler = new android.os.Handler();
    private Runnable debounceRunnable;
    private static final long DEBOUNCE_MS = 1000L;

    private final View                        root;
    private final Context                     context;
    private final WeatherParameterPreferences prefs;

    public ParametersView(View root, Context context,
                          WeatherParameterPreferences prefs) {
        this.root    = root;
        this.context = context;
        this.prefs   = prefs;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void setAvailableParameters(List<WeatherParameter> supported) {
        List<WeatherParameter> hourly  = filterBy(supported, WeatherParameter.Category.HOURLY);
        List<WeatherParameter> daily   = filterBy(supported, WeatherParameter.Category.DAILY);
        List<WeatherParameter> current = filterBy(supported, WeatherParameter.Category.CURRENT);

        bindEnumSection(R.id.params_list_hourly,
                R.id.params_all_hourly, R.id.params_none_hourly,
                hourly, WeatherParameter.MINIMUM_REQUIRED_HOURLY);
        bindEnumSection(R.id.params_list_daily,
                R.id.params_all_daily, R.id.params_none_daily,
                daily, WeatherParameter.MINIMUM_REQUIRED_DAILY);
        bindEnumSection(R.id.params_list_current,
                R.id.params_all_current, R.id.params_none_current,
                current, WeatherParameter.MINIMUM_REQUIRED_CURRENT);

        setSectionVisible(R.id.section_hourly,  !hourly.isEmpty());
        setSectionVisible(R.id.section_daily,   !daily.isEmpty());
        setSectionVisible(R.id.section_current, !current.isEmpty());
    }

    public void setDefinitionParams(
            String sourceId,
            List<com.atakmap.android.weather.data.remote.WeatherSourceDefinition.ParamEntry> hourly,
            List<com.atakmap.android.weather.data.remote.WeatherSourceDefinition.ParamEntry> daily,
            List<com.atakmap.android.weather.data.remote.WeatherSourceDefinition.ParamEntry> current) {

        android.content.SharedPreferences jsonPrefs = context.getSharedPreferences(
                "weather_json_params_" + sourceId, android.content.Context.MODE_PRIVATE);

        bindDefinitionSection(R.id.params_list_hourly,
                R.id.params_all_hourly, R.id.params_none_hourly,
                hourly, jsonPrefs, "hourly");
        bindDefinitionSection(R.id.params_list_daily,
                R.id.params_all_daily, R.id.params_none_daily,
                daily, jsonPrefs, "daily");
        bindDefinitionSection(R.id.params_list_current,
                R.id.params_all_current, R.id.params_none_current,
                current, jsonPrefs, "current");

        setSectionVisible(R.id.section_hourly,  !hourly.isEmpty());
        setSectionVisible(R.id.section_daily,   !daily.isEmpty());
        setSectionVisible(R.id.section_current, !current.isEmpty());
    }

    public void setSourceDescription(String desc) {
        TextView tv = root.findViewById(R.id.textview_parm_source_desc);
        if (tv == null) return;
        if (desc == null || desc.isEmpty()) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setText(desc);
            tv.setVisibility(View.VISIBLE);
        }
    }

    public void setOnChangeListener(OnChangeListener listener) {
        this.changeListener = listener;
    }

    // ── Private — enum sections ───────────────────────────────────────────────

    private void bindEnumSection(int containerId, int allBtnId, int noneBtnId,
                                 List<WeatherParameter> params,
                                 java.util.Set<WeatherParameter> required) {
        LinearLayout container = root.findViewById(containerId);
        if (container == null) return;
        container.removeAllViews();

        for (WeatherParameter p : params) {
            boolean checked = prefs.isSelected(p);
            CheckBox cb = makeCheckBox(
                    required.contains(p) ? p.displayName + " \uD83D\uDD12" : p.displayName,
                    checked);
            cb.setOnCheckedChangeListener((v, isChecked) -> {
                prefs.setSelected(p, isChecked);
                scheduleChangeCallback();
            });
            container.addView(cb);
        }

        Button allBtn = root.findViewById(allBtnId);
        if (allBtn != null) allBtn.setOnClickListener(v -> {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof CheckBox) ((CheckBox) child).setChecked(true);
            }
            for (WeatherParameter p : params) prefs.setSelected(p, true);
            scheduleChangeCallback();
        });
        Button noneBtn = root.findViewById(noneBtnId);
        if (noneBtn != null) noneBtn.setOnClickListener(v -> {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof CheckBox) ((CheckBox) child).setChecked(false);
            }
            for (WeatherParameter p : params) prefs.setSelected(p, false);
            scheduleChangeCallback();
        });
    }

    // ── Private — definition sections ────────────────────────────────────────

    private void bindDefinitionSection(
            int containerId, int allBtnId, int noneBtnId,
            List<com.atakmap.android.weather.data.remote.WeatherSourceDefinition.ParamEntry> entries,
            android.content.SharedPreferences jsonPrefs,
            String sectionKey) {

        LinearLayout container = root.findViewById(containerId);
        if (container == null) return;
        container.removeAllViews();

        for (com.atakmap.android.weather.data.remote.WeatherSourceDefinition.ParamEntry e : entries) {
            boolean checked = jsonPrefs.getBoolean(sectionKey + "." + e.key, e.defaultOn);
            CheckBox cb = makeCheckBox(e.label, checked);
            final String prefKey = sectionKey + "." + e.key;
            cb.setOnCheckedChangeListener((v, isChecked) -> {
                jsonPrefs.edit().putBoolean(prefKey, isChecked).apply();
                scheduleChangeCallback();
            });
            container.addView(cb);
        }

        final List<com.atakmap.android.weather.data.remote.WeatherSourceDefinition.ParamEntry>
                entriesCopy = entries;
        Button allBtn = root.findViewById(allBtnId);
        if (allBtn != null) allBtn.setOnClickListener(v -> {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof CheckBox) ((CheckBox) child).setChecked(true);
            }
            android.content.SharedPreferences.Editor ed = jsonPrefs.edit();
            for (com.atakmap.android.weather.data.remote.WeatherSourceDefinition.ParamEntry e : entriesCopy)
                ed.putBoolean(sectionKey + "." + e.key, true);
            ed.apply();
            scheduleChangeCallback();
        });
        Button noneBtn = root.findViewById(noneBtnId);
        if (noneBtn != null) noneBtn.setOnClickListener(v -> {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof CheckBox) ((CheckBox) child).setChecked(false);
            }
            android.content.SharedPreferences.Editor ed = jsonPrefs.edit();
            for (com.atakmap.android.weather.data.remote.WeatherSourceDefinition.ParamEntry e : entriesCopy)
                ed.putBoolean(sectionKey + "." + e.key, false);
            ed.apply();
            scheduleChangeCallback();
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CheckBox makeCheckBox(String label, boolean checked) {
        CheckBox cb = new CheckBox(context);
        cb.setText(label);
        cb.setChecked(checked);
        cb.setTextSize(12f);
        cb.setTextColor(android.graphics.Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 2, 0, 2);
        cb.setLayoutParams(lp);
        return cb;
    }

    private void setSectionVisible(int sectionId, boolean visible) {
        View section = root.findViewById(sectionId);
        if (section != null) section.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void scheduleChangeCallback() {
        if (changeListener == null) return;
        if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        debounceRunnable = () -> changeListener.onParametersChanged();
        debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
    }

    private static List<WeatherParameter> filterBy(List<WeatherParameter> all,
                                                   WeatherParameter.Category cat) {
        List<WeatherParameter> result = new ArrayList<>();
        for (WeatherParameter p : all) if (p.category == cat) result.add(p);
        return result;
    }
}
