
package com.atakmap.android.weather;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.coremap.maps.coords.GeoPoint;

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
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

import javax.net.ssl.HttpsURLConnection;

public class WeatherDropDownReceiver extends DropDownReceiver implements
        OnStateListener {

    public static final String TAG = WeatherDropDownReceiver.class
            .getSimpleName();

    public static final String SHOW_PLUGIN = "com.atakmap.android.weather.SHOW_PLUGIN";
    private final View templateView;
    private final Context pluginContext;
    public TextView textView;
    public ImageButton imageButton;
    public  TextView textView1;
    public  TextView textView2;
    public  TextView textView3;
    public  TextView textView4;
    public  TextView textView5;
    public SeekBar seekBar;



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
            textView1 = templateView.findViewById(R.id.textView1);
            textView2 = templateView.findViewById(R.id.textView2);
            textView3 = templateView.findViewById(R.id.textView3);
            textView4 = templateView.findViewById(R.id.textView4);
            textView5 = templateView.findViewById(R.id.textView5);
            imageButton = templateView.findViewById(R.id.imageButton);
            seekBar = templateView.findViewById(R.id.seekBar);


                    Double lat = getMapView().getSelfMarker().getPoint().getLatitude();
                    Double longt = getMapView().getSelfMarker().getPoint().getLongitude();
                    Float latf = lat.floatValue();
                    Float longtf = longt.floatValue();
                    String slat = latf.toString();
                    String slongt = longtf.toString();

            imageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Double lat1 = getMapView().getCenterPoint().get().getLatitude();
                    Double longt1 = getMapView().getCenterPoint().get().getLongitude();
                    Float latf1 = lat1.floatValue();
                    Float longtf1 = longt1.floatValue();
                    String slat = latf1.toString();
                    String slongt = longtf1.toString();
                    String url1 = "https://api.open-meteo.com/v1/forecast?latitude=" + slat + "&longitude=" + slongt + "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_hours,windspeed_10m_max,windgusts_10m_max,winddirection_10m_dominant&windspeed_unit=ms&timezone=auto";
                    new GetURLData().execute(url1);
                }
            });

                    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + slat + "&longitude=" + slongt + "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_hours,windspeed_10m_max,windgusts_10m_max,winddirection_10m_dominant&windspeed_unit=ms&timezone=auto";

                    if (slat.isEmpty()) {
                        Toast.makeText(context, R.string.set_gps, Toast.LENGTH_SHORT).show();
                        return;}
                    new GetURLData().execute(url);
                  // textView1.setText(url);

            }
        }
    private class GetURLData extends AsyncTask <String, String, String> {

        protected void onPreExecute() {
            super.onPreExecute();
            textView1.setText(R.string.wait);
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

                textView1.setText(pluginContext.getString(R.string.now) + time.toString());
                //textView2.setText(code.toString());
                if (code == String.valueOf(0)) textView3.setText(R.string.clear_sky);

                if (code == String.valueOf(1)) textView3.setText(R.string.mainly);
                if (code == String.valueOf(2)) textView3.setText(R.string.part);
                if (code == String.valueOf(3)) textView3.setText(R.string.overcast);

                if (code == String.valueOf(45)) textView3.setText(R.string.fog1);
                if (code == String.valueOf(48)) textView3.setText(R.string.fog2);

                if (code == String.valueOf(51)) textView3.setText(R.string.driz3);
                if (code == String.valueOf(53)) textView3.setText(R.string.driz2);
                if (code == String.valueOf(55))
                    textView3.setText(R.string.driz1);

                if (code == String.valueOf(56))
                    textView3.setText(R.string.frizdriz);
                if (code == String.valueOf(57))
                    textView3.setText(R.string.frizdriz1);

                if (code == String.valueOf(61)) textView3.setText(R.string.rain1);
                if (code == String.valueOf(63)) textView3.setText(R.string.rain2);
                if (code == String.valueOf(65))
                    textView3.setText(R.string.rain3);

                if (code == String.valueOf(66))
                    textView3.setText(R.string.freez1);
                if (code == String.valueOf(67))
                    textView3.setText(R.string.frez2);

                if (code == String.valueOf(71)) textView3.setText(R.string.snow1);
                if (code == String.valueOf(73)) textView3.setText(R.string.snow2);
                if (code == String.valueOf(75))
                    textView3.setText(R.string.snow3);

                if (code == String.valueOf(77)) textView3.setText(R.string.grain);

                if (code == String.valueOf(80))
                    textView3.setText(R.string.rain6);
                if (code == String.valueOf(81))
                    textView3.setText(R.string.rain4);
                if (code == String.valueOf(82))
                    textView3.setText(R.string.rain5);

                if (code == String.valueOf(85))
                    textView3.setText(R.string.snow4);
                if (code == String.valueOf(86)) textView3.setText(R.string.show5);

                if (code == String.valueOf(95)) textView3.setText(R.string.thunder);

                textView4.setText(tempmin.toString() + "C" + "/" + tempmax.toString() + "C");

                //if (precipits == String.valueOf(0)) {
                //   textView5.setText("No precipitation");}


                if (Objects.equals(precipits, "0.0")) {
                    textView5.setText(R.string.nopre);
                } else {
                    textView5.setText(pluginContext.getString(R.string.precipit) + precipits.toString() + pluginContext.getString(R.string.mm) + precipith.toString() + pluginContext.getString(R.string.hour));
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
                    //String suburb = jsonObject1.getString("suburb");
                    //String village = jsonObject1.getString("village");
                    //String town = jsonObject1.getString("town");
                    //String city = jsonObject1.getString("city");
                    //String state_district = jsonObject1.getString("state_district");
                    //String state = jsonObject1.getString("state");
                    // textView2.setText(suburb + "," + village + "," + town + "," + city + "," + state_district + "," + state);
                    String display_name = jsonObject1.getString("display_name");
                    textView2.setText(display_name);
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
