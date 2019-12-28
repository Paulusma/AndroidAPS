package info.nightscout.androidaps.plugins.hm;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.events.EventAutosensCalculationFinished;

public class ProfileSwitcherPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.PSW);
    private static ProfileSwitcherPlugin thePlugin;

    // State information
    private Profile mCurrentProfile;


    private ProfileSwitcherPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.GENERAL)
                .pluginName(R.string.profileswitcher)
                .neverVisible(true)
                .showInList(true)
                .description(R.string.description_profileswitchere)
        );
    }

    public static ProfileSwitcherPlugin getPlugin() {
        if (thePlugin == null) {
            thePlugin = new ProfileSwitcherPlugin();
        }

        return thePlugin;
    }

    @Override
    protected void onStart() {
        MainApp.bus().register(this);
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        MainApp.bus().unregister(this);
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventAutosensCalculationFinished(final EventAutosensCalculationFinished ev) {
        try {
            if (!(ev.cause instanceof EventNewBG)  || !initState(false)) return;
            executeCheck();
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private void executeCheck() {
        try {
            AutosensData autosensData = IobCobCalculatorPlugin.getPlugin().getLastAutosensData("ProfileSwitcher");
            if (autosensData != null) {
                int perc = mCurrentProfile.getPercentage();
                double ratio = autosensData.autosensResult.ratio;
                log.info("perc: "+perc+", ratio: "+ratio);
                int newPerc = 10*(int)(ratio>1.0?Math.ceil(ratio*perc/10):Math.floor(ratio*perc/10));
                if (ratio > 1.50 || ratio < 0.50) {
                    log.info("!!! RATIO out of bounds");
                }else {
                    if (newPerc != perc && (ratio >= 1.10 || ratio <= 0.90)) {
                        if (newPerc > 50 && newPerc < 150) {
                            log.info("Perc changed to: " + newPerc);
                            ProfileFunctions.doProfileSwitch(0, newPerc, 0);
                        } else {
                            log.info("New perc out of bounds: " + newPerc);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private boolean initState(boolean forceLastStatus) {
        mCurrentProfile = ProfileFunctions.getInstance().getProfile();
        return (mCurrentProfile != null);
    }
}
