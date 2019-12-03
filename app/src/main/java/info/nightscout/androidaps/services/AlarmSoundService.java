package info.nightscout.androidaps.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.IBinder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.logging.L;

public class AlarmSoundService extends Service {
    private static Logger log = LoggerFactory.getLogger(L.CORE);

    MediaPlayer player;
    int resourceId = R.raw.error;

    public AlarmSoundService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (L.isEnabled(L.CORE))
            log.debug("onCreate");
    }

    protected static int volumeBeforeAlert = -1;
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (player != null && player.isPlaying())
            player.stop();
        if (L.isEnabled(L.CORE))
            log.debug("onStartCommand");
        if (intent != null && intent.hasExtra("soundid"))
            resourceId = intent.getIntExtra("soundid", R.raw.error);

        player = new MediaPlayer();
        AssetFileDescriptor afd = MainApp.sResources.openRawResourceFd(resourceId);
        if (afd == null)
            return START_STICKY;
        try {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
        } catch (IOException e) {
            log.error("Unhandled exception", e);
        }
        AudioManager manager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null || !manager.isMusicActive()) {
            if (resourceId != R.raw.alarm && resourceId != R.raw.boluserror && resourceId != R.raw.error && resourceId != R.raw.urgentalarm  ) {
                player.setLooping(false); // Set looping
                player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        if(volumeBeforeAlert != -1) {
                            AudioManager manager = (AudioManager) MainApp.instance().getSystemService(Context.AUDIO_SERVICE);
                            manager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBeforeAlert, 0);
                            volumeBeforeAlert = -1;
                        }
                    }

                });
            }else {
                player.setLooping(true); // Set looping
            }
            if(volumeBeforeAlert == -1) { //TODO test operation whit 2 or more simultaneous notifications
                volumeBeforeAlert = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int index = (int)manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2;
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
            }
        }

        try {
            player.prepare();
            player.start();
            } catch (IOException e) {
            log.error("Unhandled exception", e);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (player != null) {
            player.stop();
            player.release();
        }

        if (L.isEnabled(L.CORE))
            log.debug("onDestroy");
    }
}
