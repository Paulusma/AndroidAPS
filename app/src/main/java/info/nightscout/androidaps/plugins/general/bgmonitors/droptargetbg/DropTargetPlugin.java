package info.nightscout.androidaps.plugins.general.bgmonitors.droptargetbg;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.events.EventReloadProfileSwitchData;
import info.nightscout.androidaps.events.EventTempTargetChange;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.CobInfo;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;

import static info.nightscout.androidaps.utils.DateUtil.now;

/**
 * Created by Paulusma on 16/07/19.
 * <p>
 * Features:
 * <p>
 * * drops target BG if BG is stable for some time
 * - starts TT with configurable level
 * - only starts TT if no TT running at that moment
 * - cancels TT when BG stable condition no longer applies
 */
public class DropTargetPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.DROPTARGET);
    private static DropTargetPlugin dropTargetPlugin;

    // State information
    private TempTarget runningTT = null;
    private GlucoseStatus lastStatus = null;
    IobTotal iobTotal = null;
    private CobInfo cobInfo = null;

    private long timeLastStableBG = 0;

    private Profile currentProfile = null;

    public static DropTargetPlugin getPlugin() {
        if (dropTargetPlugin == null) {
            dropTargetPlugin = new DropTargetPlugin();
        }

        return dropTargetPlugin;
    }

    private DropTargetPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.GENERAL)
                .pluginName(R.string.droptarget)
                .shortName(R.string.droptarget_shortname)
                .neverVisible(true)
                .preferencesId(R.xml.pref_droptarget)
                .description(R.string.description_droptarget)
        );
    }

    @Override
    protected void onStart() {
        MainApp.bus().register(this);
        super.onStart();

        try {
            // Check after AndroidAPS restart if we had a TT running and sync with that
            if (isEnabled(PluginType.GENERAL)) {
                synchronized (this) {
                    currentProfile = ProfileFunctions.getInstance().getProfile();
                    lastStatus = GlucoseStatus.getGlucoseStatusData(true);
                    cobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
                    if (currentProfile != null)
                        iobTotal = IobCobCalculatorPlugin.getPlugin().calculateFromTreatmentsAndTempsSynchronized(now(), currentProfile);

                    TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
                    if (currentTarget != null && currentTarget.reason.equals(MainApp.gs(R.string.stable_bg))) {
                        runningTT = currentTarget;
                    }
                    executeCheck();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        MainApp.bus().unregister(this);
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventNewBG(final EventNewBG ev) {
        if (!isEnabled(PluginType.GENERAL) || currentProfile == null) {
            return;
        }
        try {
            if (ev.bgReading != null) {
                lastStatus = GlucoseStatus.getGlucoseStatusData(true);
                cobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
                iobTotal = IobCobCalculatorPlugin.getPlugin().calculateFromTreatmentsAndTempsSynchronized(now(), currentProfile);
                executeCheck();
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventTempTargetChange(final EventTempTargetChange ev) {
        if (!isEnabled(PluginType.GENERAL) || currentProfile == null) {
            return;
        }
        if (lastStatus == null) {
            lastStatus = GlucoseStatus.getGlucoseStatusData(true);
            cobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
            iobTotal = IobCobCalculatorPlugin.getPlugin().calculateFromTreatmentsAndTempsSynchronized(now(), currentProfile);
        }

        try {
            TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
            if (ttRunning()) {
                if (currentTarget != null) {
                    if (runningTT.date != currentTarget.date) {
                        endTT(false);
                        // Will not check if TT should be started: any other TT overrides TT's from this plugin
                    }
                } else {
                    // don't allow manual cancellation of our TT
                    endTT(false); // sync
                    executeCheck();
                }
            } else {
                executeCheck();
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onStatusEvent(final EventReloadProfileSwitchData ev) {
        currentProfile = ProfileFunctions.getInstance().getProfile();
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventPreferenceChange(final EventPreferenceChange ev) {
        if (!isEnabled(PluginType.GENERAL) || currentProfile == null) {
            return;
        }
        if (lastStatus == null) {
            lastStatus = GlucoseStatus.getGlucoseStatusData(true);
            cobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
            iobTotal = IobCobCalculatorPlugin.getPlugin().calculateFromTreatmentsAndTempsSynchronized(now(), currentProfile);
        }

        try {
            if (ev.isChanged(R.string.key_droptarget_TT_bg) ||
                    ev.isChanged(R.string.key_droptarget_24hwindow) ||
                    ev.isChanged(R.string.key_droptarget_window_to) ||
                    ev.isChanged(R.string.key_droptarget_window_from) ||
                    ev.isChanged(R.string.key_droptarget_waittime)) {

                // Sync state with changed preferences
                executeCheck();
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private void executeCheck() {
        try {
            if (lastStatus == null && iobTotal != null)
                return;

            if (ttRunning()) {
                if (!stableBG()) {
                    endTT(true);
                }
            } else if (stableBG()) {
                TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
                if (currentTarget == null) {
                    double ttTargetLevel = Profile.toMgdl(SP.getDouble(R.string.key_droptarget_TT_bg, 0d)
                            , currentProfile.getUnits());
                    int duration = 180; // TT will be cancelled when precondition no longer satisfied

                    starTT(ttTargetLevel, duration);
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    // TODO: stable BG started while running (hypo) TT?
    private boolean stableBG() {
        if (lastStatus == null)
            return false;

        // Check if we only guard in specific time frame
        boolean useFrame = SP.getBoolean(R.string.key_droptarget_24hwindow, false);
        long frameStart = SP.getLong(R.string.key_droptarget_window_from, 0L);
        long frameEnd = SP.getLong(R.string.key_droptarget_window_to, 0L);
        int time = Profile.secondsFromMidnight() / 60;
        if (useFrame && (time < frameStart || time > frameEnd)) {
            return false;
        }

        double sens = Profile.toMgdl(currentProfile.getIsf(), currentProfile.getUnits());
        if ((lastStatus.short_avgdelta < 5 && lastStatus.short_avgdelta > -5)
                && (lastStatus.long_avgdelta < 5 && lastStatus.long_avgdelta > -5)
                && (lastStatus.delta < 5 && lastStatus.delta > -5)
                && lastStatus.glucose - iobTotal.iob * sens > 4 * 18
                && cobInfo.displayCob == 0){

                timeLastStableBG = now();
                return true;
            }
            return false;
        }

        private boolean ttRunning () {
            return (runningTT != null);
        }

        private void starTT ( double target, int duration){
            runningTT = new TempTarget()
                    .date(DateUtil.roundDateToSec(now()))
                    .duration(duration)
                    .reason(MainApp.gs(R.string.stable_bg))
                    .source(Source.USER)
                    .low(target)
                    .high(target);
            TreatmentsPlugin.getPlugin().addToHistoryTempTarget(runningTT);
        }

        private void endTT ( boolean wait){
            if (wait) {
                long waitTimeMins = SP.getLong(R.string.key_droptarget_waittime, 0L);
                if (now() < timeLastStableBG + waitTimeMins * 60 * 1000)
                    return;

                TempTarget tempTT = new TempTarget()
                        .source(Source.USER)
                        .date(DateUtil.roundDateToSec(now()))
                        .reason(MainApp.gs(R.string.stable_bg))
                        .duration(0)
                        .low(0)
                        .high(0);
                TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTT);
            }
            // Also reset plugin state to 'idle'
            runningTT = null;
            timeLastStableBG = 0;
        }
    }
