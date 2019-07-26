package info.nightscout.androidaps.plugins.general.monitors;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.events.EventTempTargetChange;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.DefaultValueHelper;
import info.nightscout.androidaps.utils.SP;

import static info.nightscout.androidaps.utils.DateUtil.now;

/**
 * Created by Paulusma on 16/07/19.
 */
public class HypoPredictorPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.AUTOSENS);
    private static HypoPredictorPlugin hypoPredictorPlugin;

    // State information
    private TempTarget runningHypoTT = null;
    private TempTarget previousTT = null;
    private long timePCLastSatisfied = 0;

    public static HypoPredictorPlugin getPlugin() {
        if (hypoPredictorPlugin == null) {
            hypoPredictorPlugin = new HypoPredictorPlugin();
        }

        return hypoPredictorPlugin;
    }

    private HypoPredictorPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.GENERAL)
                .pluginName(R.string.hypoppred)
                .shortName(R.string.hypoppred_shortname)
                .neverVisible(true)
                .preferencesId(R.xml.pref_hypopredictor)
                .description(R.string.description_hypoppred)
        );
    }

    @Override
    protected void onStart() {
        MainApp.bus().register(this);
        super.onStart();

        // Check after AndroidAPS restart if we had a TT running
        TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
        if(currentTarget != null && currentTarget.reason.equals(MainApp.gs(R.string.hypo_detection))){
            runningHypoTT = currentTarget;
            timePCLastSatisfied = now();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        MainApp.bus().unregister(this);
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onEventNewBG(final EventNewBG ev) {
        if (this != getPlugin()) {
            if (L.isEnabled(L.AUTOSENS))
                log.debug("Ignoring event for non default instance");
            return;
        }
        if(ev.bgReading != null)
            executeCheck(ev.bgReading.value);
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onEventTempTargetChange(final EventTempTargetChange ev) {
        try {
            if (this != getPlugin()) {
                if (L.isEnabled(L.AUTOSENS))
                    log.debug("Ignoring event for non default instance");
                return;
            }

            TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
            if (pluginIsRunning()) {
                if (currentTarget != null) {
                    if (runningHypoTT.date != currentTarget.date)
                        endHypoTT(false); // allow, sync
                } else {
                    // don't allow manual cancellation of our TT
                    endHypoTT(false); // sync
                    executeCheck(0);
                }
            }else{
                executeCheck(0);
            }
        } catch (
                Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onEventPreferenceChange(final EventPreferenceChange ev) {
        try {
            if (ev.isChanged(R.string.key_hypoppred_threshold_bg) ||
                    ev.isChanged(R.string.key_hypoppred_24hwindow) ||
                    ev.isChanged(R.string.key_hypoppred_window_to) ||
                    ev.isChanged(R.string.key_hypoppred_window_from)) {

                // Sync state with changed preferences
                executeCheck(0);
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private boolean preConditionSatisfied(double lastBG) {
        final Profile currentProfile = ProfileFunctions.getInstance().getProfile();
        if (currentProfile == null) {
            return false;
        }

        double bgThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , currentProfile.getUnits());
        boolean useFrame = SP.getBoolean(R.string.key_hypoppred_24hwindow, false);
        long frameStart = SP.getLong(R.string.key_hypoppred_window_from, 0L);
        long frameEnd = SP.getLong(R.string.key_hypoppred_window_to, 0L);
        int time = Profile.secondsFromMidnight()/60;

        if ((lastBG <= bgThreshold)
                && (!useFrame || (time >= frameStart && time < frameEnd))) {
            timePCLastSatisfied = now();
            return true;
        }
        return false;
    }

    // Threadsafe to prevent checks on inconsistent state
    private synchronized void executeCheck(double lastBG) {
        try {
            if (lastBG == 0) {
                BgReading bgReading = DatabaseHelper.lastBg();
                if (bgReading == null)
                    return;
                lastBG = bgReading.value;
            }

            if (pluginIsRunning()) {
                if (!preConditionSatisfied(lastBG)) {
                    endHypoTT(true);
                }
            }else if(preConditionSatisfied(lastBG)) {
                final Profile currentProfile = ProfileFunctions.getInstance().getProfile();
                if (currentProfile == null) {
                    return;
                }
                final String units = currentProfile.getUnits();
                DefaultValueHelper helper = new DefaultValueHelper();
                double target = Profile.toMgdl(helper.determineHypoTT(units), units);
                int duration = 180; // TT will be cancelled when precondition no longer satisfied

                TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
                if (currentTarget == null || currentTarget.low < target)
                    startHypoTT(currentTarget, target, duration);
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private boolean pluginIsRunning() {
        return (runningHypoTT != null);
    }

    private void startHypoTT(TempTarget currentTarget, double target, int duration) {
        if (currentTarget != null) {
            previousTT = new TempTarget();
            previousTT.copyFrom(currentTarget);
        }

        runningHypoTT = new TempTarget()
                .date(DateUtil.roundDateToSec(now()))
                .duration(duration)
                .reason(MainApp.gs(R.string.hypo_detection))
                .source(Source.USER)
                .low(target)
                .high(target);
        TreatmentsPlugin.getPlugin().addToHistoryTempTarget(runningHypoTT);
    }

    private void endHypoTT(boolean wait15Min) {
        if (wait15Min) {
            if (now() < timePCLastSatisfied + 15 * 60 * 1000)
                return;

            if (previousTT != null && previousTT.isInProgress()) {
                // Previous TT would still be in progress so let it run for the remainder of its original duration
                int minutesRemaining = (int) (previousTT.end() - now()) / 60 / 1000;
                previousTT.date(DateUtil.roundDateToSec(now()))
                        .duration(minutesRemaining)
                        .reason(previousTT.reason+" (continued)");
                TreatmentsPlugin.getPlugin().addToHistoryTempTarget(previousTT);
            } else {
                TempTarget tempTT = new TempTarget()
                        .source(Source.USER)
                        .date(DateUtil.roundDateToSec(now()))
                        .duration(0)
                        .low(0)
                        .high(0);
                TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTT);
            }
        }

        // Also reset plugin state to 'idle'
        runningHypoTT = null;
        previousTT = null;
        timePCLastSatisfied = 0;
    }

}
