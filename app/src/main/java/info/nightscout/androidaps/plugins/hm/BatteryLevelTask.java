package info.nightscout.androidaps.plugins.hm;

import android.app.Activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;

/*
 Check battery level. Warn when <20%
 */
public class BatteryLevelTask implements Runnable {
    private static Logger log = LoggerFactory.getLogger(L.HMTASK);

    private Activity act;
    private static long lastWarning = 0L;

    public BatteryLevelTask(Activity _act) {
        act = _act;
    }

    @Override
    public void run() {
        try {
            int batteryLevel = BatteryUtils.getLevel();
            boolean isCharging = BatteryUtils.isCharging();
            if(batteryLevel < 20 && !isCharging && System.currentTimeMillis() - lastWarning > 30*60*1000) {
                Notification n = new Notification(Notification.HYPO_ALERT, "Batterij raakt leeg!", Notification.URGENT);
                n.soundId = R.raw.urgentalarm;
                MainApp.bus().post(new EventNewNotification(n));
                lastWarning = System.currentTimeMillis();
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.info( e.getMessage());
        } finally {
        }
    }


}