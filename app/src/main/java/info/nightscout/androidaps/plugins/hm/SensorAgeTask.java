package info.nightscout.androidaps.plugins.hm;

import android.app.Activity;
import android.content.Intent;
import android.support.v4.content.ContextCompat;
import android.widget.TextView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.services.AlarmSoundService;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;

/*
 Call xDrip REST service to display sensor age. Warn when >13d old.
 */
public class SensorAgeTask implements Runnable {
    private static Logger log = LoggerFactory.getLogger(L.HMTASK);

    private Activity act;
    private TextView sensorAgeView;
    private static long lastCalled = 0;
    private static String lastSensorAge = "";

    public SensorAgeTask(Activity _act, TextView _tv) {
        act = _act;
        sensorAgeView = _tv;
    }

    @Override
    public void run() {
        HttpURLConnection myConnection = null;
        String currentSensorAge;
        try {
            // update age only once every half hour
            if (DateUtil.now() - lastCalled > 1000 * 60 * 30) {
                URL xDripCall = new URL("http://127.0.0.1:17580/sgv.json?sensor=Y");
                myConnection = (HttpURLConnection) xDripCall.openConnection();
                if (myConnection.getResponseCode() == 200) {
                    InputStream responseBody = myConnection.getInputStream();
                    InputStreamReader responseBodyReader = new InputStreamReader(responseBody, "UTF-8");
                    JsonArray bgReadings = new JsonParser().parse(responseBodyReader).getAsJsonArray();
                    JsonObject obj = bgReadings.get(0).getAsJsonObject();
                    currentSensorAge = obj.get("sensor_status").getAsString();
                } else {
                    log.info("Error calling xDrip REST: " + myConnection.getResponseCode() + myConnection.getResponseMessage());
                    myConnection.disconnect();
                    return;
                }
            } else
                currentSensorAge = lastSensorAge;


            final String sensorAge = currentSensorAge;
            act.runOnUiThread(new Runnable() { // UI operations must run on this thread
                @Override
                public void run() {
                    try {
                        sensorAgeView.setText("Sensor Age: ??.?");
                        double sAge = 0.0d;
                        sAge = Double.parseDouble(sensorAge.substring(4, sensorAge.length() - 1));
                        int time = Profile.secondsFromMidnight() / 60;
                        if (sAge >= 13.0 &&
                                !SP.getBoolean("sensor_replace_warned", Boolean.FALSE) &&
                                time > 10 * 60 && time < 23 * 60) {
                            // Over time, not yet warned and during waking hours: warn "place new sensor"
                            SP.putBoolean("sensor_replace_warned", Boolean.TRUE);
                            Intent alarm = new Intent(MainApp.instance().getApplicationContext(), AlarmSoundService.class);
                            alarm.putExtra("soundid", R.raw.prewarn_new_sensor);
                            MainApp.instance().startService(alarm);
                            sensorAgeView.setText("PLACE NEW SENSOR (" + sensorAge+")");
                            sensorAgeView.setTextColor(ContextCompat.getColor(MainApp.instance().getApplicationContext(), R.color.warning));
                            log.info("xDrip sensor age updated, prewarned");
                        } else if (sAge < 13.0) {
                            SP.putBoolean("sensor_replace_warned", Boolean.FALSE);
                            sensorAgeView.setText("Sensor " + sensorAge);
                            sensorAgeView.setTextColor(ContextCompat.getColor(MainApp.instance().getApplicationContext(), R.color.colorLightGray));
                            log.info("Sensor prewarn reset");
                        }
                        SP.putDouble("last_sensor_age", sAge);
                        lastCalled = DateUtil.now();
                        lastSensorAge = currentSensorAge;
                    } catch (Exception e) {
                        log.info("" + e);
                        e.printStackTrace();
                        log.info(e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            log.info("Exception starting xDrip REST: " + e.getMessage());
        } finally {
            if (myConnection != null)
                myConnection.disconnect();
        }
    }
}