
package com.atakmap.android.weather;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
//import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.ImageButton;
//import android.widget.SeekBar;
import android.widget.TabHost;
import android.widget.TabHost.TabSpec;
import android.widget.TextView;
import android.widget.Toast;

//import com.atakmap.coremap.maps.coords.GeoPoint;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;

import com.atakmap.coremap.log.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
//import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

import javax.net.ssl.HttpsURLConnection;

public class WeatherDropDownReceiver extends DropDownReceiver implements
        OnStateListener {

    // ATAK Plugin variables
    public static final String TAG = WeatherDropDownReceiver.class
            .getSimpleName();
    public static final String SHOW_PLUGIN = "com.atakmap.android.weather.SHOW_PLUGIN";
    private final View templateView;
    private final Context pluginContext;

    // -------                  -------
    // ------- Global variables -------
    // -------                  -------
    // https://open-meteo.com/
    private String stOpenMeteoBaseUrl = "https://api.open-meteo.com/v1/forecast?";
    private String stLatOMBU  = "latitude=";
    private String stLongOMBU = "&longitude=";

    // Hourly Weather Variables
    private String stHourlyRequest = "&hourly=temperature_2m,relativehumidity_2m,apparent_temperature,precipitation_probability,precipitation,weathercode,surface_pressure,visibility,windspeed_10m,winddirection_10m";
    private String stDailyRequest  = "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_hours,precipitation_probability_max";
    private String stWindSpeedUnit = "&windspeed_unit=ms";
    private String stTimeZone = "&timezone=auto";

    // https://nominatim.org/release-docs/latest/
    private String stOpenStreetNominatim = "https://nominatim.openstreetmap.org/reverse?format=json?";
    private String stLatitudeOSN = "&lat=";
    private String stLongitudeOSN = "&lon=";
    private String stZoomParam ="&zoom=14";
    private String stAddrDetailsParam = "&addressdetails=1";

    // -------                    -------
    // ------- Layout Declaration -------
    // -------                    -------
    public TabHost tabHost;
    public TabSpec spec;

    // Tab 1 XML declaration
    public ImageButton imageButton;
    public TextView textview_date;
    public TextView textview_town;
    public ImageView image;
    public TextView textview_airT;
    public TextView textview_airTrf;
    public TextView textview_visibility;
    public TextView textview_humidity;
    public TextView textview_pressure;
    public TextView textview_wind;
    public TextView textview_weather;
    public TextView textview_precipitation;

    //public SeekBar seekBar;

    /**************************** CONSTRUCTOR *****************************/

    public WeatherDropDownReceiver(final MapView mapView,
            final Context context) {
        super(mapView);
        this.pluginContext = context;

        // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
        // In this case, using it is not necessary - but I am putting it here to remind
        // developers to look at this Inflator
        templateView = PluginLayoutInflater.inflate(context,
                R.layout.main_layout, null);


    }

    /**************************** PUBLIC METHODS *****************************/

    public void disposeImpl() {
    }

    /**************************** INHERITED METHODS *****************************/

    @Override
    public void onReceive(final Context context, Intent intent) {

        final String action = intent.getAction();
        if (action == null)
            return;

        if (action.equals(SHOW_PLUGIN)) {

            Log.d(TAG, "showing plugin drop down");
            showDropDown(templateView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH,
                    HALF_HEIGHT, false, this);

            // initiating the tabhost
            tabHost = templateView.findViewById(R.id.mainTabHost);

            // setting up the tabhost
            tabHost.setup();

            // code for adding tab 1 to the tabhost
            spec = tabHost.newTabSpec("Forecasting");
            spec.setContent(R.id.subTabWidget1);
            // setting the name of the tab 1 as "Tab One"
            spec.setIndicator("Forecasting");
            // adding the tab to tabhost
            tabHost.addTab(spec);

            // code for adding tab 2 to the tabhost
            spec = tabHost.newTabSpec("Overlay");
            spec.setContent(R.id.subTabWidget2);

            // setting the name of the tab 1 as "Tab Two"
            spec.setIndicator("Overlay");
            tabHost.addTab(spec);

            // code for adding the Tab 3 to the tabhost
            spec = tabHost.newTabSpec("TBD");
            spec.setContent(R.id.subTabWidget3);
            spec.setIndicator("TBD");
            tabHost.addTab(spec);

            // Tab 1 : init
            imageButton = templateView.findViewById(R.id.imageButton);
            imageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    double dLatitude1  = getMapView().getSelfMarker().getPoint().getLatitude();
                    double dLongitude1 = getMapView().getSelfMarker().getPoint().getLongitude();

                    float fLatitude = (float)dLatitude1;
                    float fLongitude = (float)dLongitude1;

                    String sLatitude  = "" + fLatitude;
                    String sLongitude = "" + fLongitude;

                    // Create the URL to update values
                    String url1 = stOpenMeteoBaseUrl + stLatOMBU + sLatitude + stLongOMBU + sLongitude + stHourlyRequest + stDailyRequest + stWindSpeedUnit + stTimeZone;
                    new GetURLData().execute(url1);
                }
            });

            textview_date = templateView.findViewById(R.id.textView_date);
            textview_town = templateView.findViewById(R.id.textView_town);

            image = templateView.findViewById(R.id.image);

            textview_airT = templateView.findViewById(R.id.textview_airT);
            textview_airTrf = templateView.findViewById(R.id.textview_airTrf);
            textview_visibility = templateView.findViewById(R.id.textview_visibility);

            textview_weather = templateView.findViewById(R.id.textview_weather);
            textview_humidity = templateView.findViewById(R.id.textview_humidity);
            textview_pressure = templateView.findViewById(R.id.textview_pressure);
            textview_wind = templateView.findViewById(R.id.textview_wind);
            textview_precipitation = templateView.findViewById(R.id.textview_precipitation);

            //seekBar = templateView.findViewById(R.id.seekBar);


            // First API call
            double dLatitude  = getMapView().getSelfMarker().getPoint().getLatitude();
            double dLongitude = getMapView().getSelfMarker().getPoint().getLongitude();

            float fLatitude = (float)dLatitude;
            float fLongitude = (float)dLongitude;

            String sLatitude  = "" + fLatitude;
            String sLongitude = "" + fLongitude;

            String url = stOpenMeteoBaseUrl + stLatOMBU + sLatitude + stLongOMBU + sLongitude + stHourlyRequest + stDailyRequest + stWindSpeedUnit + stTimeZone;
            if (sLatitude.isEmpty()) {
                Toast.makeText(context, R.string.set_gps, Toast.LENGTH_SHORT).show();
                return;
            }
            new GetURLData().execute(url);
            textview_date.setText(url);
        }
    }
    private class GetURLData extends AsyncTask <String, String, String> {

        protected void onPreExecute() {
            super.onPreExecute();
            textview_date.setText(R.string.wait);
        }

        @SuppressLint("SuspiciousIndentation")
        @Override
        protected String doInBackground(String... strings) {
            HttpsURLConnection connection = null;
            BufferedReader reader = null;

            try {
                URL url = new URL(strings[0]);
                connection = (HttpsURLConnection) url.openConnection();
                connection.connect();

                InputStream stream = connection.getInputStream();
                reader = new BufferedReader(new InputStreamReader(stream));

                StringBuffer buffer = new StringBuffer();
                String line = " ";

                while ((line = reader.readLine()) != null)

                    buffer.append(line).append("\n");

                return buffer.toString();

            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (connection != null)
                    connection.disconnect();
                try {
                    if (reader != null)
                        reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        @SuppressLint("SetTextI18n")
        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            try {
                JSONObject jsonObject = new JSONObject(result);
                Double latitude = jsonObject.getDouble("latitude");
                Double longitude = jsonObject.getDouble("longitude");

                String time = jsonObject.getJSONObject("daily").getJSONArray("time").getString(0);
                String code = jsonObject.getJSONObject("daily").getJSONArray("weathercode").getString(0);
                String tempmax = jsonObject.getJSONObject("daily").getJSONArray("temperature_2m_max").getString(0);
                String tempmin = jsonObject.getJSONObject("daily").getJSONArray("temperature_2m_min").getString(0);
                String precipits = jsonObject.getJSONObject("daily").getJSONArray("precipitation_sum").getString(0);
                String precipith = jsonObject.getJSONObject("daily").getJSONArray("precipitation_hours").getString(0);
                String preciptpm = jsonObject.getJSONObject("daily").getJSONArray("precipitation_probability_max").getString(0);

                String temp2m      = jsonObject.getJSONObject("hourly").getJSONArray("temperature_2m").getString(0);
                String relhumidity = jsonObject.getJSONObject("hourly").getJSONArray("relativehumidity_2m").getString(0);
                String apptemp     = jsonObject.getJSONObject("hourly").getJSONArray("apparent_temperature").getString(0);
                String preciptprob = jsonObject.getJSONObject("hourly").getJSONArray("precipitation_probability").getString(0);
                String surfpress   = jsonObject.getJSONObject("hourly").getJSONArray("surface_pressure").getString(0);
                String visibility  = jsonObject.getJSONObject("hourly").getJSONArray("visibility").getString(0);
                String windspeed   = jsonObject.getJSONObject("hourly").getJSONArray("windspeed_10m").getString(0);
                String winddir     = jsonObject.getJSONObject("hourly").getJSONArray("winddirection_10m").getString(0);
                // not used : precipitation_probability, precipitation, weathercode (hourly)

                textview_date.setText(pluginContext.getString(R.string.now) + time);
                textview_town.setText(code);

                textview_airT.setText(tempmin + "°C" + " / " + tempmax + "°C");
                textview_airTrf.setText(apptemp + "°C");
                textview_visibility.setText(visibility + " meters");

                // change associated image regarding WMO Weather Interpretation Code
                switch(Integer.parseInt(code)) {
                    case 0:
                        textview_weather.setText(R.string.clear_sky);
                        image.setImageResource(R.drawable.wc_00d);
                        break;
                    case 1:
                        textview_weather.setText(R.string.mainly);
                        image.setImageResource(R.drawable.wc_01d);
                        break;
                    case 2:
                        textview_weather.setText(R.string.part);
                        image.setImageResource(R.drawable.wc_02d);
                        break;
                    case 3:
                        textview_weather.setText(R.string.overcast);
                        image.setImageResource(R.drawable.wc_03d);
                        break;
                    case 45:
                        textview_weather.setText(R.string.fog1);
                        image.setImageResource(R.drawable.wc_45d);
                        break;
                    case 48:
                        textview_weather.setText(R.string.fog2);
                        image.setImageResource(R.drawable.wc_48d);
                        break;
                    case 51:
                        textview_weather.setText(R.string.driz3);
                        image.setImageResource(R.drawable.wc_51d);
                        break;
                    case 53:
                        textview_weather.setText(R.string.driz2);
                        image.setImageResource(R.drawable.wc_53d);
                        break;
                    case 55:
                        textview_weather.setText(R.string.driz1);
                        image.setImageResource(R.drawable.wc_55d);
                        break;
                    case 56:
                        textview_weather.setText(R.string.frizdriz);
                        image.setImageResource(R.drawable.wc_56d);
                        break;
                    case 57:
                        textview_weather.setText(R.string.frizdriz1);
                        image.setImageResource(R.drawable.wc_57d);
                        break;
                    case 61:
                        textview_weather.setText(R.string.rain1);
                        image.setImageResource(R.drawable.wc_61d);
                        break;
                    case 63:
                        textview_weather.setText(R.string.rain2);
                        image.setImageResource(R.drawable.wc_63d);
                        break;
                    case 65:
                        textview_weather.setText(R.string.rain3);
                        image.setImageResource(R.drawable.wc_65d);
                        break;
                    case 66:
                        textview_weather.setText(R.string.freez1);
                        image.setImageResource(R.drawable.wc_66d);
                        break;
                    case 67:
                        textview_weather.setText(R.string.freez2);
                        image.setImageResource(R.drawable.wc_67d);
                        break;
                    case 71:
                        textview_weather.setText(R.string.snow1);
                        image.setImageResource(R.drawable.wc_71d);
                        break;
                    case 73:
                        textview_weather.setText(R.string.snow2);
                        image.setImageResource(R.drawable.wc_73d);
                        break;
                    case 75:
                        textview_weather.setText(R.string.snow3);
                        image.setImageResource(R.drawable.wc_75d);
                        break;
                    case 77:
                        textview_weather.setText(R.string.grain);
                        image.setImageResource(R.drawable.wc_77d);
                        break;
                    case 80:
                        textview_weather.setText(R.string.rain6);
                        image.setImageResource(R.drawable.wc_80d);
                        break;
                    case 81:
                        textview_weather.setText(R.string.rain4);
                        image.setImageResource(R.drawable.wc_81d);
                        break;
                    case 82:
                        textview_weather.setText(R.string.rain5);
                        image.setImageResource(R.drawable.wc_82d);
                        break;
                    case 85:
                        textview_weather.setText(R.string.snow4);
                        image.setImageResource(R.drawable.wc_85d);
                        break;
                    case 86:
                        textview_weather.setText(R.string.snow5);
                        image.setImageResource(R.drawable.wc_86d);
                        break;
                    case 95:
                        textview_weather.setText(R.string.thunder1);
                        image.setImageResource(R.drawable.wc_95d);
                        break;
                    case 96:
                        textview_weather.setText(R.string.thunder2);
                        image.setImageResource(R.drawable.wc_96d);
                        break;
                    case 99:
                        textview_weather.setText(R.string.thunder3);
                        image.setImageResource(R.drawable.wc_99d);
                        break;
                }

                textview_humidity.setText(relhumidity + " %");
                textview_pressure.setText(surfpress + " hPa");
                textview_wind.setText(windspeed + " m/s / " + winddir +"°");

                if (Objects.equals(precipits, "0.0")) {
                    textview_precipitation.setText(R.string.nopre);
                } else {
                    textview_precipitation.setText(precipits +" "+ pluginContext.getString(R.string.mm) + " " + precipith + " " + pluginContext.getString(R.string.hour));
                }


                String url1 = "https://nominatim.openstreetmap.org/reverse?format=json&lat=" + latitude + "&lon=" + longitude + "&zoom=14&addressdetails=1";
                new GetGeocodeData().execute(url1);

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }


        private class GetGeocodeData extends AsyncTask<String, String, String> {

            @SuppressLint("SuspiciousIndentation")
            @Override
            protected String doInBackground(String... strings) {
                HttpsURLConnection connection1 = null;
                BufferedReader reader1 = null;

                try {
                    URL url1 = new URL(strings[0]);
                    connection1 = (HttpsURLConnection) url1.openConnection();
                    connection1.connect();

                    InputStream stream = connection1.getInputStream();
                    reader1 = new BufferedReader(new InputStreamReader(stream));

                    StringBuffer buffer1 = new StringBuffer();
                    String line1 = " ";

                    while ((line1 = reader1.readLine()) != null)

                        buffer1.append(line1).append("\n");

                    return buffer1.toString();

                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (connection1 != null)
                        connection1.disconnect();
                    try {
                        if (reader1 != null)
                            reader1.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(String result1) {
                super.onPostExecute(result1);
                try {
                    JSONObject jsonObject1 = new JSONObject(result1);
                    String display_name = jsonObject1.getString("display_name");
                    textview_town.setText(display_name);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

        }
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

}
