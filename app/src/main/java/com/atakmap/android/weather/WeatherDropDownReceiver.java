
package com.atakmap.android.weather;

import android.annotation.SuppressLint;
import android.content.AsyncQueryHandler;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.AsyncTask;
//import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
//import android.widget.SeekBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TabHost;
import android.widget.TabHost.TabSpec;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

//import com.atakmap.coremap.maps.coords.GeoPoint;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
//import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.chrono.ChronoLocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.time.LocalDateTime;

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

    private String stWindInformationAltitude= "&hourly=temperature_2m,windspeed_10m,windspeed_80m,windspeed_120m,windspeed_180m,winddirection_10m,winddirection_80m,winddirection_120m,winddirection_180m,windgusts_10m,temperature_80m,temperature_120m,temperature_180m&windspeed_unit=ms";

    // https://nominatim.org/release-docs/latest/
    private String stOpenStreetNominatim = "https://nominatim.openstreetmap.org/reverse?format=json?";
    private String stLatitudeOSN = "&lat=";
    private String stLongitudeOSN = "&lon=";
    private String stZoomParam ="&zoom=14";
    private String stAddrDetailsParam = "&addressdetails=1";

    private String json_request_time;
    private String[] daily_time;
    private String[] daily_weathercode;
    private String[] daily_temperature_2m_max;
    private String[] daily_temperature_2m_min;
    private String[] daily_precipitation_sum;
    private String[] daily_precipitation_hours;
    private String[] daily_precipitation_probability_max;

    private String[] hourly_time;
    private String[] hourly_temperature_2m;
    private String[] hourly_relativehumidity_2m;
    private String[] hourly_apparent_temperature;
    private String[] hourly_precipitation_probability;
    private String[] hourly_surface_pressure;
    private String[] hourly_visibility;
    private String[] hourly_windspeed_10m;
    private String[] hourly_winddirection_10m;

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

    public SeekBar seekBar;

    public GridLayout gridLayout_daily_forecast;
    public TextView textview_daily_forecast_day;
    public TextView textview_daily_forecast_date;
    public ImageView imageview_daily_forecast_weatherimage;
    public TextView textview_daily_forecast_max_temp;
    public TextView textview_daily_forecast_min_temp;
    public TextView textview_daily_forecast_weathertext;

    public TableLayout[] tablelayout_daily_forecast_loop;
    public TextView[] textview_daily_forecast_day_loop;
    public TextView[] textview_daily_forecast_date_loop;
    public ImageView[] imageview_daily_forecast_weatherimage_loop;
    public TextView[] textview_daily_forecast_max_temp_loop;
    public TextView[] textview_daily_forecast_min_temp_loop;
    public TextView[] textview_daily_forecast_weathertext_loop;
    // tabview3

    public TextView textview_designator;
    public Button button_wind_information;
    public TextView textview_wind_information;

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

    /**************************** PRIVATE METHODS *****************************/
    @SuppressLint("SetTextI18n")
    private void seekBarUpdateUI(int i) {
        textview_date.setText(pluginContext.getString(R.string.now) + json_request_time + " / forecast to +0" + hourly_time[i] + " hours");


        textview_airT.setText(daily_temperature_2m_min[i] + "°C" + " / " + daily_temperature_2m_max[i] + "°C");
        textview_airTrf.setText(hourly_apparent_temperature[i] + "°C");
        textview_visibility.setText(hourly_visibility[i] + " meters");

        // change associated image regarding WMO Weather Interpretation Code
        switch(Integer.parseInt(daily_weathercode[i])) {
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

        textview_humidity.setText(hourly_relativehumidity_2m[i] + " %");
        textview_pressure.setText(hourly_surface_pressure[i] + " hPa");
        textview_wind.setText(hourly_windspeed_10m[i] + " m/s / " + hourly_winddirection_10m[i] +"°");

        if (Objects.equals(daily_precipitation_sum[i], "0.0")) {
            textview_precipitation.setText(R.string.nopre);
        } else {
            textview_precipitation.setText(daily_precipitation_sum[i] +" "+ pluginContext.getString(R.string.mm) + " " + daily_precipitation_hours[i] + " " + pluginContext.getString(R.string.hour));
        }
    }
    private int dpToPx(int dp) {
        float density = templateView.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
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
            //tabHost = templateView.findViewById(R.id.mainTabHost);
            if (tabHost == null) {
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

                // setting the name of the tab 2 as "Tab Two"
                spec.setIndicator("Overlay");
                tabHost.addTab(spec);

                // code for adding the Tab 3 to the tabhost
                spec = tabHost.newTabSpec("SandBox");
                spec.setContent(R.id.subTabWidget3);
                spec.setIndicator("SandBox");
                tabHost.addTab(spec);

                // Tab 1 : init
                imageButton = templateView.findViewById(R.id.imageButton);
                imageButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        double dLatitude1 = getMapView().getCenterPoint().get().getLatitude();
                        double dLongitude1 = getMapView().getCenterPoint().get().getLongitude();

                        float fLatitude = (float) dLatitude1;
                        float fLongitude = (float) dLongitude1;

                        String sLatitude = "" + fLatitude;
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

                seekBar = templateView.findViewById(R.id.seekBar);
                seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                        seekBarUpdateUI(i);
                        // Handle SeekBar progress change
                        // You can perform actions based on the new progress value
                        // For example, update a TextView to display the progress
                        // textView.setText("Progress: " + progress);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        // Called when user starts interacting with the SeekBar
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        // Called when user stops interacting with the SeekBar
                    }
                });

                gridLayout_daily_forecast = templateView.findViewById(R.id.daily_forecast_gridlayout);
                tablelayout_daily_forecast_loop = new TableLayout[7];
                textview_daily_forecast_day_loop = new TextView[7];
                textview_daily_forecast_date_loop = new TextView[7];
                imageview_daily_forecast_weatherimage_loop = new ImageView[7];
                textview_daily_forecast_max_temp_loop = new TextView[7];
                textview_daily_forecast_min_temp_loop = new TextView[7];
                textview_daily_forecast_weathertext_loop = new TextView[7];

                tablelayout_daily_forecast_loop[0] = templateView.findViewById(R.id.daily_forecast_tablelayout_1);
                textview_daily_forecast_day_loop[0] = templateView.findViewById(R.id.daily_forecast_day_textview_1);
                textview_daily_forecast_date_loop[0]  = templateView.findViewById(R.id.daily_forecast_date_textview_1);
                imageview_daily_forecast_weatherimage_loop[0]  = templateView.findViewById(R.id.daily_forecast_weathercode_imageview_1);
                textview_daily_forecast_max_temp_loop[0]  = templateView.findViewById(R.id.daily_forecast_temp_max_textview_1);
                textview_daily_forecast_min_temp_loop[0]  = templateView.findViewById(R.id.daily_forecast_temp_min_textview_1);
                textview_daily_forecast_weathertext_loop[0]  = templateView.findViewById(R.id.daily_forecast_weatherinfo_textview_1);

                tablelayout_daily_forecast_loop[1] = templateView.findViewById(R.id.daily_forecast_tablelayout_2);
                textview_daily_forecast_day_loop[1] = templateView.findViewById(R.id.daily_forecast_day_textview_2);
                textview_daily_forecast_date_loop[1]  = templateView.findViewById(R.id.daily_forecast_date_textview_2);
                imageview_daily_forecast_weatherimage_loop[1]  = templateView.findViewById(R.id.daily_forecast_weathercode_imageview_2);
                textview_daily_forecast_max_temp_loop[1]  = templateView.findViewById(R.id.daily_forecast_temp_max_textview_2);
                textview_daily_forecast_min_temp_loop[1]  = templateView.findViewById(R.id.daily_forecast_temp_min_textview_2);
                textview_daily_forecast_weathertext_loop[1]  = templateView.findViewById(R.id.daily_forecast_weatherinfo_textview_2);

                tablelayout_daily_forecast_loop[2] = templateView.findViewById(R.id.daily_forecast_tablelayout_3);
                textview_daily_forecast_day_loop[2] = templateView.findViewById(R.id.daily_forecast_day_textview_3);
                textview_daily_forecast_date_loop[2]  = templateView.findViewById(R.id.daily_forecast_date_textview_3);
                imageview_daily_forecast_weatherimage_loop[2]  = templateView.findViewById(R.id.daily_forecast_weathercode_imageview_3);
                textview_daily_forecast_max_temp_loop[2]  = templateView.findViewById(R.id.daily_forecast_temp_max_textview_3);
                textview_daily_forecast_min_temp_loop[2]  = templateView.findViewById(R.id.daily_forecast_temp_min_textview_3);
                textview_daily_forecast_weathertext_loop[2]  = templateView.findViewById(R.id.daily_forecast_weatherinfo_textview_3);

                tablelayout_daily_forecast_loop[3] = templateView.findViewById(R.id.daily_forecast_tablelayout_4);
                textview_daily_forecast_day_loop[3] = templateView.findViewById(R.id.daily_forecast_day_textview_4);
                textview_daily_forecast_date_loop[3]  = templateView.findViewById(R.id.daily_forecast_date_textview_4);
                imageview_daily_forecast_weatherimage_loop[3]  = templateView.findViewById(R.id.daily_forecast_weathercode_imageview_4);
                textview_daily_forecast_max_temp_loop[3]  = templateView.findViewById(R.id.daily_forecast_temp_max_textview_4);
                textview_daily_forecast_min_temp_loop[3]  = templateView.findViewById(R.id.daily_forecast_temp_min_textview_4);
                textview_daily_forecast_weathertext_loop[3]  = templateView.findViewById(R.id.daily_forecast_weatherinfo_textview_4);

                tablelayout_daily_forecast_loop[4] = templateView.findViewById(R.id.daily_forecast_tablelayout_5);
                textview_daily_forecast_day_loop[4] = templateView.findViewById(R.id.daily_forecast_day_textview_5);
                textview_daily_forecast_date_loop[4]  = templateView.findViewById(R.id.daily_forecast_date_textview_5);
                imageview_daily_forecast_weatherimage_loop[4]  = templateView.findViewById(R.id.daily_forecast_weathercode_imageview_5);
                textview_daily_forecast_max_temp_loop[4]  = templateView.findViewById(R.id.daily_forecast_temp_max_textview_5);
                textview_daily_forecast_min_temp_loop[4]  = templateView.findViewById(R.id.daily_forecast_temp_min_textview_5);
                textview_daily_forecast_weathertext_loop[4]  = templateView.findViewById(R.id.daily_forecast_weatherinfo_textview_5);

                tablelayout_daily_forecast_loop[5] = templateView.findViewById(R.id.daily_forecast_tablelayout_6);
                textview_daily_forecast_day_loop[5] = templateView.findViewById(R.id.daily_forecast_day_textview_6);
                textview_daily_forecast_date_loop[5]  = templateView.findViewById(R.id.daily_forecast_date_textview_6);
                imageview_daily_forecast_weatherimage_loop[5]  = templateView.findViewById(R.id.daily_forecast_weathercode_imageview_6);
                textview_daily_forecast_max_temp_loop[5]  = templateView.findViewById(R.id.daily_forecast_temp_max_textview_6);
                textview_daily_forecast_min_temp_loop[5]  = templateView.findViewById(R.id.daily_forecast_temp_min_textview_6);
                textview_daily_forecast_weathertext_loop[5]  = templateView.findViewById(R.id.daily_forecast_weatherinfo_textview_6);

                tablelayout_daily_forecast_loop[6] = templateView.findViewById(R.id.daily_forecast_tablelayout_7);
                textview_daily_forecast_day_loop[6] = templateView.findViewById(R.id.daily_forecast_day_textview_7);
                textview_daily_forecast_date_loop[6]  = templateView.findViewById(R.id.daily_forecast_date_textview_7);
                imageview_daily_forecast_weatherimage_loop[6]  = templateView.findViewById(R.id.daily_forecast_weathercode_imageview_7);
                textview_daily_forecast_max_temp_loop[6]  = templateView.findViewById(R.id.daily_forecast_temp_max_textview_7);
                textview_daily_forecast_min_temp_loop[6]  = templateView.findViewById(R.id.daily_forecast_temp_min_textview_7);
                textview_daily_forecast_weathertext_loop[6]  = templateView.findViewById(R.id.daily_forecast_weatherinfo_textview_7);

                // tabView 2 init

                // tabView 3 init
                button_wind_information = templateView.findViewById(R.id.wind_update_information_button);
                button_wind_information.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        double dLatitude1 = getMapView().getCenterPoint().get().getLatitude();
                        double dLongitude1 = getMapView().getCenterPoint().get().getLongitude();

                        float fLatitude = (float) dLatitude1;
                        float fLongitude = (float) dLongitude1;

                        String sLatitude = "" + fLatitude;
                        String sLongitude = "" + fLongitude;

                        // Create the URL to update values
                        String url1 = stOpenMeteoBaseUrl + stLatOMBU + sLatitude + stLongOMBU + sLongitude + stWindInformationAltitude;
                        new GetURL_WindData().execute(url1);
                    }
                });
                textview_wind_information = templateView.findViewById(R.id.textview_tab3_waiting_json_data);


                //Log.d(TAG, "Get geopoint from designator : " +  Double.toString(d_latitude) + " : " + Double.toString(d_longitude));
            }

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
            if (textview_date != null) {textview_date.setText(url);}
        }
    }
    private class GetURL_WindData extends AsyncTask <String, String, String> {
        protected void onPreExecute() {
            super.onPreExecute();
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
            if (result == null) {
                Log.e(TAG, "onPostExecute() : error, result is null or empty");

            } else {
                try {
                    JSONObject jsonObject = new JSONObject(result);
                    Double latitude = jsonObject.getDouble("latitude");
                    Double longitude = jsonObject.getDouble("longitude");

                    JSONArray hourly_values = jsonObject.getJSONObject("hourly").getJSONArray("time");
                    textview_wind_information.setText(jsonObject.toString());
                    String url1 = "https://nominatim.openstreetmap.org/reverse?format=json&lat=" + latitude + "&lon=" + longitude + "&zoom=14&addressdetails=1";
                    new GetURL_WindData.GetGeocodeData().execute(url1);

                } catch (JSONException e) {
                    e.printStackTrace();
                }
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
                if (result1 != null && !result1.isEmpty()) {
                    try {
                        JSONObject jsonObject1 = new JSONObject(result1);
                        String display_name = jsonObject1.getString("display_name");
                        textview_town.setText(display_name);
                    } catch (JSONException e) {
                        e.printStackTrace();
                        textview_town.setText("Error occurred while parsing JSON");
                    }
                } else {
                    textview_town.setText("Empty or null JSON result");
                }
            }

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
            if (result == null) {
                Log.e(TAG, "onPostExecute() : error, result is null or empty");

            } else {
                try {
                    LocalDateTime on_json_request = LocalDateTime.now();
                    String date_time_pattern = "yyyy-MM-dd HH:mm:ss";
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(date_time_pattern);
                    json_request_time = on_json_request.format(formatter);

                    JSONObject jsonObject = new JSONObject(result);
                    Double latitude = jsonObject.getDouble("latitude");
                    Double longitude = jsonObject.getDouble("longitude");

                    JSONArray daily_values = jsonObject.getJSONObject("daily").getJSONArray("time");
                    Log.d(TAG, "Extracted JSONArray : " + daily_values);
                    daily_time = new String[daily_values.length()];
                    daily_weathercode = new String[daily_values.length()];
                    daily_temperature_2m_max = new String[daily_values.length()];
                    daily_temperature_2m_min = new String[daily_values.length()];
                    daily_precipitation_sum = new String[daily_values.length()];
                    daily_precipitation_hours = new String[daily_values.length()];
                    daily_precipitation_probability_max = new String[daily_values.length()];
                    for (int i = 0; i < daily_values.length(); i++) {
                        daily_time[i] = jsonObject.getJSONObject("daily").getJSONArray("time").getString(i);
                        daily_weathercode[i] = jsonObject.getJSONObject("daily").getJSONArray("weathercode").getString(i);
                        daily_temperature_2m_max[i] = jsonObject.getJSONObject("daily").getJSONArray("temperature_2m_max").getString(i);
                        daily_temperature_2m_min[i] = jsonObject.getJSONObject("daily").getJSONArray("temperature_2m_min").getString(i);
                        daily_precipitation_sum[i] = jsonObject.getJSONObject("daily").getJSONArray("precipitation_sum").getString(0);
                        daily_precipitation_hours[i] = jsonObject.getJSONObject("daily").getJSONArray("precipitation_hours").getString(0);
                        daily_precipitation_probability_max[i] = jsonObject.getJSONObject("daily").getJSONArray("precipitation_probability_max").getString(0);

                    }

                    JSONArray hourly_values = jsonObject.getJSONObject("hourly").getJSONArray("time");
                    hourly_time = new String[hourly_values.length()];
                    hourly_temperature_2m = new String[hourly_values.length()];
                    hourly_relativehumidity_2m = new String[hourly_values.length()];
                    hourly_apparent_temperature = new String[hourly_values.length()];
                    hourly_precipitation_probability = new String[hourly_values.length()];
                    hourly_surface_pressure = new String[hourly_values.length()];
                    hourly_visibility = new String[hourly_values.length()];
                    hourly_windspeed_10m = new String[hourly_values.length()];
                    hourly_winddirection_10m = new String[hourly_values.length()];
                    for (int i = 0; i < daily_values.length(); i++) {
                        String htime = jsonObject.getJSONObject("hourly").getJSONArray("time").getString(i);
                        String htime_pattern = "yyyy-MM-dd'T'HH:mm";
                        DateTimeFormatter htime_formatter = DateTimeFormatter.ofPattern(htime_pattern);
                        LocalDateTime htime_date = LocalDateTime.parse(htime, htime_formatter);

                        hourly_time[i] = String.valueOf(htime_date.getHour());
                        hourly_temperature_2m[i] = jsonObject.getJSONObject("hourly").getJSONArray("temperature_2m").getString(i);
                        hourly_relativehumidity_2m[i] = jsonObject.getJSONObject("hourly").getJSONArray("relativehumidity_2m").getString(i);
                        hourly_apparent_temperature[i] = jsonObject.getJSONObject("hourly").getJSONArray("apparent_temperature").getString(i);
                        hourly_precipitation_probability[i] = jsonObject.getJSONObject("hourly").getJSONArray("precipitation_probability").getString(i);
                        hourly_surface_pressure[i] = jsonObject.getJSONObject("hourly").getJSONArray("surface_pressure").getString(i);
                        hourly_visibility[i] = jsonObject.getJSONObject("hourly").getJSONArray("visibility").getString(i);
                        hourly_windspeed_10m[i] = jsonObject.getJSONObject("hourly").getJSONArray("windspeed_10m").getString(i);
                        hourly_winddirection_10m[i] = jsonObject.getJSONObject("hourly").getJSONArray("winddirection_10m").getString(i);
                    }

                    textview_date.setText(pluginContext.getString(R.string.now) + json_request_time + " / forecast to +0" + hourly_time[0] + " hours");


                    textview_airT.setText(daily_temperature_2m_min[0] + "°C" + " / " + daily_temperature_2m_max[0] + "°C");
                    textview_airTrf.setText(hourly_apparent_temperature[0] + "°C");
                    textview_visibility.setText(hourly_visibility[0] + " meters");

                    // change associated image regarding WMO Weather Interpretation Code
                    switch (Integer.parseInt(daily_weathercode[0])) {
                        case 0:
                            textview_weather.setText(R.string.clear_sky);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.clear_sky);
                            image.setImageResource(R.drawable.wc_00d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_00d);
                            break;
                        case 1:
                            textview_weather.setText(R.string.mainly);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.mainly);
                            image.setImageResource(R.drawable.wc_01d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_01d);
                            break;
                        case 2:
                            textview_weather.setText(R.string.part);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.part);
                            image.setImageResource(R.drawable.wc_02d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_02d);
                            break;
                        case 3:
                            textview_weather.setText(R.string.overcast);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.overcast);
                            image.setImageResource(R.drawable.wc_03d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_03d);
                            break;
                        case 45:
                            textview_weather.setText(R.string.fog1);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.fog1);
                            image.setImageResource(R.drawable.wc_45d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_45d);
                            break;
                        case 48:
                            textview_weather.setText(R.string.fog2);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.fog2);
                            image.setImageResource(R.drawable.wc_48d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_48d);
                            break;
                        case 51:
                            textview_weather.setText(R.string.driz3);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.driz3);
                            image.setImageResource(R.drawable.wc_51d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_51d);
                            break;
                        case 53:
                            textview_weather.setText(R.string.driz2);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.driz2);
                            image.setImageResource(R.drawable.wc_53d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_53d);
                            break;
                        case 55:
                            textview_weather.setText(R.string.driz1);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.driz1);
                            image.setImageResource(R.drawable.wc_55d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_55d);
                            break;
                        case 56:
                            textview_weather.setText(R.string.frizdriz);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.frizdriz);
                            image.setImageResource(R.drawable.wc_56d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_56d);
                            break;
                        case 57:
                            textview_weather.setText(R.string.frizdriz1);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.frizdriz1);
                            image.setImageResource(R.drawable.wc_57d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_57d);
                            break;
                        case 61:
                            textview_weather.setText(R.string.rain1);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.rain1);
                            image.setImageResource(R.drawable.wc_61d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_61d);
                            break;
                        case 63:
                            textview_weather.setText(R.string.rain2);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.rain2);
                            image.setImageResource(R.drawable.wc_63d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_63d);
                            break;
                        case 65:
                            textview_weather.setText(R.string.rain3);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.rain3);
                            image.setImageResource(R.drawable.wc_65d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_65d);
                            break;
                        case 66:
                            textview_weather.setText(R.string.freez1);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.freez1);
                            image.setImageResource(R.drawable.wc_66d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_66d);
                            break;
                        case 67:
                            textview_weather.setText(R.string.freez2);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.freez2);
                            image.setImageResource(R.drawable.wc_67d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_67d);
                            break;
                        case 71:
                            textview_weather.setText(R.string.snow1);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.snow1);
                            image.setImageResource(R.drawable.wc_71d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_71d);
                            break;
                        case 73:
                            textview_weather.setText(R.string.snow2);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.snow2);
                            image.setImageResource(R.drawable.wc_73d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_73d);
                            break;
                        case 75:
                            textview_weather.setText(R.string.snow3);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.snow3);
                            image.setImageResource(R.drawable.wc_75d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_75d);
                            break;
                        case 77:
                            textview_weather.setText(R.string.grain);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.grain);
                            image.setImageResource(R.drawable.wc_77d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_77d);
                            break;
                        case 80:
                            textview_weather.setText(R.string.rain6);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.rain6);
                            image.setImageResource(R.drawable.wc_80d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_80d);
                            break;
                        case 81:
                            textview_weather.setText(R.string.rain4);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.rain4);
                            image.setImageResource(R.drawable.wc_81d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_81d);
                            break;
                        case 82:
                            textview_weather.setText(R.string.rain5);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.rain5);
                            image.setImageResource(R.drawable.wc_82d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_82d);
                            break;
                        case 85:
                            textview_weather.setText(R.string.snow4);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.snow4);
                            image.setImageResource(R.drawable.wc_85d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_85d);
                            break;
                        case 86:
                            textview_weather.setText(R.string.snow5);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.snow5);
                            image.setImageResource(R.drawable.wc_86d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_86d);
                            break;
                        case 95:
                            textview_weather.setText(R.string.thunder1);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.thunder1);
                            image.setImageResource(R.drawable.wc_95d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_95d);
                            break;
                        case 96:
                            textview_weather.setText(R.string.thunder2);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.thunder2);
                            image.setImageResource(R.drawable.wc_96d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_96d);
                            break;
                        case 99:
                            textview_weather.setText(R.string.thunder3);
                            textview_daily_forecast_weathertext_loop[0].setText(R.string.thunder2);
                            image.setImageResource(R.drawable.wc_99d);
                            imageview_daily_forecast_weatherimage_loop[0].setImageResource(R.drawable.wc_99d);
                            break;
                    }

                    textview_humidity.setText(hourly_relativehumidity_2m[0] + " %");
                    textview_pressure.setText(hourly_surface_pressure[0] + " hPa");
                    textview_wind.setText(hourly_windspeed_10m[0] + " m/s / " + hourly_winddirection_10m[0] + "°");

                    if (Objects.equals(daily_precipitation_sum[0], "0.0")) {
                        textview_precipitation.setText(R.string.nopre);
                    } else {
                        textview_precipitation.setText(daily_precipitation_sum[0] + " " + pluginContext.getString(R.string.mm) + " " + daily_precipitation_hours[0] + " " + pluginContext.getString(R.string.hour));
                    }

                    // set the daily forecast
                    // Generate the first day Forecast layout
                    textview_daily_forecast_day_loop[0].setText("Today");
                    textview_daily_forecast_date_loop[0].setText(daily_time[0]);
                    // imageview weathercode set before
                    textview_daily_forecast_max_temp_loop[0].setText("High: " + daily_temperature_2m_max[0] + "°C");
                    textview_daily_forecast_min_temp_loop[0].setText("Low: " + daily_temperature_2m_min[0] + "°C");
                    // textview weather text set before

                    DateTimeFormatter dayformatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                    LocalDate date = LocalDate.parse(daily_time[1], dayformatter);
                    DayOfWeek dayOfWeek = date.getDayOfWeek();

                    // Generate the second day Forecast layout
                    textview_daily_forecast_day_loop[1].setText(dayOfWeek.toString());
                    textview_daily_forecast_date_loop[1].setText(daily_time[1]);
                    // imageview weathercode set before
                    textview_daily_forecast_max_temp_loop[1].setText("High: " + daily_temperature_2m_max[1] + "°C");
                    textview_daily_forecast_min_temp_loop[1].setText("Low: " + daily_temperature_2m_min[1] + "°C");
                    // textview weather text set before
                    switch (Integer.parseInt(daily_weathercode[1])) {
                        case 0:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.clear_sky);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_00d);
                            break;
                        case 1:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.mainly);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_01d);
                            break;
                        case 2:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.part);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_02d);
                            break;
                        case 3:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.overcast);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_03d);
                            break;
                        case 45:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.fog1);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_45d);
                            break;
                        case 48:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.fog2);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_48d);
                            break;
                        case 51:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.driz3);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_51d);
                            break;
                        case 53:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.driz2);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_53d);
                            break;
                        case 55:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.driz1);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_55d);
                            break;
                        case 56:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.frizdriz);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_56d);
                            break;
                        case 57:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.frizdriz1);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_57d);
                            break;
                        case 61:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.rain1);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_61d);
                            break;
                        case 63:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.rain2);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_63d);
                            break;
                        case 65:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.rain3);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_65d);
                            break;
                        case 66:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.freez1);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_66d);
                            break;
                        case 67:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.freez2);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_67d);
                            break;
                        case 71:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.snow1);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_71d);
                            break;
                        case 73:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.snow2);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_73d);
                            break;
                        case 75:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.snow3);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_75d);
                            break;
                        case 77:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.grain);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_77d);
                            break;
                        case 80:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.rain6);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_80d);
                            break;
                        case 81:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.rain4);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_81d);
                            break;
                        case 82:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.rain5);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_82d);
                            break;
                        case 85:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.snow4);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_85d);
                            break;
                        case 86:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.snow5);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_86d);
                            break;
                        case 95:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.thunder1);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_95d);
                            break;
                        case 96:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.thunder2);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_96d);
                            break;
                        case 99:
                            textview_daily_forecast_weathertext_loop[1].setText(R.string.thunder2);
                            imageview_daily_forecast_weatherimage_loop[1].setImageResource(R.drawable.wc_99d);
                            break;
                    }

                    // Generate the third day Forecast layout
                    date = LocalDate.parse(daily_time[2]);
                    dayOfWeek = date.getDayOfWeek();
                    textview_daily_forecast_day_loop[2].setText(dayOfWeek.toString());
                    textview_daily_forecast_date_loop[2].setText(daily_time[2]);
                    // imageview weathercode set before
                    textview_daily_forecast_max_temp_loop[2].setText("High: " + daily_temperature_2m_max[2] + "°C");
                    textview_daily_forecast_min_temp_loop[2].setText("Low: " + daily_temperature_2m_min[2] + "°C");
                    // textview weather text set before
                    switch (Integer.parseInt(daily_weathercode[2])) {
                        case 0:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.clear_sky);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_00d);
                            break;
                        case 1:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.mainly);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_01d);
                            break;
                        case 2:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.part);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_02d);
                            break;
                        case 3:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.overcast);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_03d);
                            break;
                        case 45:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.fog1);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_45d);
                            break;
                        case 48:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.fog2);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_48d);
                            break;
                        case 51:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.driz3);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_51d);
                            break;
                        case 53:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.driz2);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_53d);
                            break;
                        case 55:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.driz1);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_55d);
                            break;
                        case 56:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.frizdriz);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_56d);
                            break;
                        case 57:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.frizdriz1);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_57d);
                            break;
                        case 61:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.rain1);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_61d);
                            break;
                        case 63:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.rain2);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_63d);
                            break;
                        case 65:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.rain3);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_65d);
                            break;
                        case 66:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.freez1);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_66d);
                            break;
                        case 67:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.freez2);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_67d);
                            break;
                        case 71:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.snow1);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_71d);
                            break;
                        case 73:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.snow2);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_73d);
                            break;
                        case 75:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.snow3);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_75d);
                            break;
                        case 77:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.grain);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_77d);
                            break;
                        case 80:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.rain6);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_80d);
                            break;
                        case 81:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.rain4);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_81d);
                            break;
                        case 82:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.rain5);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_82d);
                            break;
                        case 85:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.snow4);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_85d);
                            break;
                        case 86:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.snow5);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_86d);
                            break;
                        case 95:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.thunder1);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_95d);
                            break;
                        case 96:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.thunder2);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_96d);
                            break;
                        case 99:
                            textview_daily_forecast_weathertext_loop[2].setText(R.string.thunder2);
                            imageview_daily_forecast_weatherimage_loop[2].setImageResource(R.drawable.wc_99d);
                            break;
                    }

                    // Generate the fourth day Forecast layout
                    date = LocalDate.parse(daily_time[3]);
                    dayOfWeek = date.getDayOfWeek();
                    textview_daily_forecast_day_loop[3].setText(dayOfWeek.toString());
                    textview_daily_forecast_date_loop[3].setText(daily_time[3]);
                    // imageview weathercode set before
                    textview_daily_forecast_max_temp_loop[3].setText("High: " + daily_temperature_2m_max[3] + "°C");
                    textview_daily_forecast_min_temp_loop[3].setText("Low: " + daily_temperature_2m_min[3] + "°C");
                    // textview weather text set before
                    switch (Integer.parseInt(daily_weathercode[3])) {
                        case 0:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.clear_sky);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_00d);
                            break;
                        case 1:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.mainly);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_01d);
                            break;
                        case 2:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.part);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_02d);
                            break;
                        case 3:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.overcast);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_03d);
                            break;
                        case 45:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.fog1);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_45d);
                            break;
                        case 48:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.fog2);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_48d);
                            break;
                        case 51:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.driz3);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_51d);
                            break;
                        case 53:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.driz2);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_53d);
                            break;
                        case 55:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.driz1);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_55d);
                            break;
                        case 56:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.frizdriz);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_56d);
                            break;
                        case 57:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.frizdriz1);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_57d);
                            break;
                        case 61:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.rain1);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_61d);
                            break;
                        case 63:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.rain2);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_63d);
                            break;
                        case 65:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.rain3);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_65d);
                            break;
                        case 66:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.freez1);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_66d);
                            break;
                        case 67:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.freez2);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_67d);
                            break;
                        case 71:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.snow1);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_71d);
                            break;
                        case 73:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.snow2);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_73d);
                            break;
                        case 75:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.snow3);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_75d);
                            break;
                        case 77:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.grain);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_77d);
                            break;
                        case 80:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.rain6);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_80d);
                            break;
                        case 81:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.rain4);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_81d);
                            break;
                        case 82:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.rain5);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_82d);
                            break;
                        case 85:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.snow4);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_85d);
                            break;
                        case 86:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.snow5);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_86d);
                            break;
                        case 95:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.thunder1);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_95d);
                            break;
                        case 96:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.thunder2);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_96d);
                            break;
                        case 99:
                            textview_daily_forecast_weathertext_loop[3].setText(R.string.thunder2);
                            imageview_daily_forecast_weatherimage_loop[3].setImageResource(R.drawable.wc_99d);
                            break;
                    }

                    String url1 = "https://nominatim.openstreetmap.org/reverse?format=json&lat=" + latitude + "&lon=" + longitude + "&zoom=14&addressdetails=1";
                    new GetGeocodeData().execute(url1);

                } catch (JSONException e) {
                    e.printStackTrace();
                }
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
                if (result1 != null && !result1.isEmpty()) {
                    try {
                        JSONObject jsonObject1 = new JSONObject(result1);
                        String display_name = jsonObject1.getString("display_name");
                        textview_town.setText(display_name);
                    } catch (JSONException e) {
                        e.printStackTrace();
                        textview_town.setText("Error occurred while parsing JSON");
                    }
                } else {
                    textview_town.setText("Empty or null JSON result");
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
