
package com.atakmap.android.weather;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.dropdown.DropDownMapComponent;

import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.android.weather.infrastructure.preferences.WeatherPreferenceFragment;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;
import com.atakmap.android.weather.plugin.R;

import java.util.List;

public class WeatherMapComponent extends DropDownMapComponent {

    private static final String TAG = "WeatherMapComponent";

    private Context pluginContext;

    private MapView pluginMapView;

    private WeatherDropDownReceiver ddr;

    private SharedPreferences sharedPref;

    public void onCreate(final Context context, Intent intent,
            final MapView view) {

        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);
        pluginContext = context;

        pluginMapView = view;

        ddr = new WeatherDropDownReceiver(
                view, context);

        Log.d(TAG, "registering the plugin filter");
        DocumentedIntentFilter ddFilter = new DocumentedIntentFilter();
        ddFilter.addAction(WeatherDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);

        // Custom preferences
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        "Weather Preferences",
                        "Preferences for the Weather Plugin",
                        "weatherPreference",
                        context.getResources().getDrawable(
                                R.drawable.ic_launcher, null),
                        new WeatherPreferenceFragment(context)));
//        sharedPref.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
//        });

        getSelfMarkerCot();
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        super.onDestroyImpl(context, view);
    }

    private void getSelfMarkerCot() {
        String callsign = "PAMPA";
        Log.d(TAG, "callsign : " + callsign);
        Contact contact = Contacts.getInstance().getFirstContactWithCallsign(callsign);
        List<Contact> contact_list = Contacts.getInstance().getAllContacts();

        for (int i = 0; i < contact_list.size(); i++) {
            Contact contact1 = contact_list.get(i);
            String contact1_uid = contact1.getUid();
            CotEvent contact_cotEvent = CotEventFactory.createCotEvent(pluginMapView.getMapItem(contact1_uid));
            Log.d(TAG, "contact : " + contact_cotEvent);
        }

        // Create our CoT message and the detail with our detail
        CotDetail cotDetail = new CotDetail();
        com.atakmap.android.maps.Marker selfMarker = pluginMapView.getMapView().getSelfMarker();
        CotEvent cotEvent = CotEventFactory.createCotEvent(selfMarker);

        cotEvent.setDetail(cotDetail);

        CotMapComponent.getExternalDispatcher().dispatchToContact(cotEvent, contact);

        com.atakmap.android.maps.Marker selfMarker_2 = ATAKUtilities.findSelfUnplaced(MapView.getMapView()); // Standard CoT broadcast message CotDetail cotDetail
        Log.d(TAG, "selfMarker : " + selfMarker);
        Log.d(TAG, "selfMarker_2 : " + selfMarker_2);
        Log.d(TAG, "cotEvent : " + cotEvent);
    }
}
