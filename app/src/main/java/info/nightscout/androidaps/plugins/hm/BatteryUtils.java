package info.nightscout.androidaps.plugins.hm;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import info.nightscout.androidaps.MainApp;

public class BatteryUtils {

    public static Intent getBatteryIntent() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        return MainApp.instance().registerReceiver(null, ifilter);
    }

    public static int getScale() {
        Intent intent = getBatteryIntent();
        return intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
    }

    public static int getLevel( ) {
        Intent intent = getBatteryIntent();
        return intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    }

    public static int getChargeStatus( ) {
        Intent intent = getBatteryIntent();
        return intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
    }

    public static boolean isCharging(int status) {
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    public static boolean isCharging() {
        int status = getChargeStatus();
        return isCharging(status);
    }

}
