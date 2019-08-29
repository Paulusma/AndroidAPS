package info.nightscout.androidaps.plugins.general.bgmonitors.dropbgtarget;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.events.EventTempTargetChange;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.CobInfo;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.events.EventAutosensCalculationFinished;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;

import static info.nightscout.androidaps.utils.DateUtil.now;

public class DropBGTargetPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.DROPTARGET);
    private static DropBGTargetPlugin thePlugin;

    // State information
    private Profile mCurrentProfile = null;
    private TempTarget mCurrentTarget = null;
    private GlucoseStatus mLastStatus = null;
    private CobInfo mCobInfo = null;
    private IobTotal mIobTotal = null;
    private long mTimeLastStableBG = 0;

    private DropBGTargetPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.GENERAL)
                .pluginName(R.string.droptarget)
                .shortName(R.string.droptarget_shortname)
                .neverVisible(true)
                .preferencesId(R.xml.pref_droptarget)
                .description(R.string.description_droptarget)
        );
    }

    public static DropBGTargetPlugin getPlugin() {
        if (thePlugin == null) {
            thePlugin = new DropBGTargetPlugin();
        }

        return thePlugin;
    }

    @Override
    protected void onStart() {
        MainApp.bus().register(this);
        super.onStart();

        if (isEnabled(PluginType.GENERAL)) return;

        try {
            // Check after AndroidAPS restart if we had a TT running and sync with that
            synchronized (this) {
                initState(true);
                executeCheck();
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
    public synchronized void onEventAutosensCalculationFinished(final EventAutosensCalculationFinished ev) {
        try {
            if (!isEnabled(PluginType.GENERAL) || !initState(true)) return;
            executeCheck();
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventTempTargetChange(final EventTempTargetChange ev) {
        try {
            if (!isEnabled(PluginType.GENERAL) || !initState(false)) return;
            executeCheck();
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventPreferenceChange(final EventPreferenceChange ev) {
        try {
            if (!isEnabled(PluginType.GENERAL) || !initState(false)) return;

            if (ev.isChanged(R.string.key_droptarget_TT_bg) ||
                    ev.isChanged(R.string.key_droptarget_24hwindow) ||
                    ev.isChanged(R.string.key_droptarget_window_to) ||
                    ev.isChanged(R.string.key_droptarget_window_from) ||
                    ev.isChanged(R.string.key_droptarget_waittime)) {
                executeCheck();
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private void executeCheck() {
        try {
            if (mLastStatus == null || mIobTotal == null || mCobInfo == null) return;

            boolean pcSatisfied = preconditionSatisfied();
            if (runningTT()) {
                if (!pcSatisfied) {
                    endTT();
                } else
                    log.info("Keep TT running");
            } else if (pcSatisfied) {
                if (mCurrentTarget == null) {
                    double ttTargetLevel = Profile.toMgdl(SP.getDouble(R.string.key_droptarget_TT_bg, 0d)
                            , mCurrentProfile.getUnits());
                    int duration = 180; // TT will be cancelled when stableBG condition no longer satisfied

                    startTT(ttTargetLevel, duration);
                } else
                    log.info("Can't start TT (there is another TT running)");
            };

        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private boolean preconditionSatisfied() {

        // Check if we only guard in specific time frame
        boolean useFrame = SP.getBoolean(R.string.key_droptarget_24hwindow, false);
        long frameStart = SP.getLong(R.string.key_droptarget_window_from, 0L);
        long frameEnd = SP.getLong(R.string.key_droptarget_window_to, 0L);
        int time = Profile.secondsFromMidnight() / 60;
        if (useFrame && (time < frameStart || time > frameEnd)) {
            log.info("Outside of timeframe");
            return false;
        }

        double sens = Profile.toMgdl(mCurrentProfile.getIsf(), mCurrentProfile.getUnits());
        if ((mLastStatus.short_avgdelta < 5 && mLastStatus.short_avgdelta > -5)
                && (mLastStatus.long_avgdelta < 5 && mLastStatus.long_avgdelta > -5)
                && (mLastStatus.delta < 5 && mLastStatus.delta > -5)
                && mLastStatus.glucose - mIobTotal.iob * sens > 4 * 18
//                && mCobInfo.displayCob == 0
                && mLastStatus.glucose > 4 * 18 && mLastStatus.glucose < 8 * 18) {

            mTimeLastStableBG = now();
            log.info("Precondition satisfied");
            return true;
        }
        log.info("Precondition NOT satisfied");
        return false;
    }

    private boolean runningTT() {
        boolean running = (mCurrentTarget != null && mCurrentTarget.reason.startsWith(MainApp.gs(R.string.stable_bg)));
        log.info("TT running: " + running);

        return running;
    }

    private void startTT(double target, int duration) {
        log.info("Starting TT");
        TreatmentsPlugin.getPlugin().addToHistoryTempTarget(new TempTarget()
                .date(DateUtil.roundDateToSec(now()))
                .duration(duration)
                .reason(MainApp.gs(R.string.stable_bg))
                .source(Source.USER)
                .low(target)
                .high(target));
    }

    private void endTT() {
        long waitTimeMins = SP.getLong(R.string.key_droptarget_waittime, 0L);
        if (now() < mTimeLastStableBG + waitTimeMins * 60 * 1000) {
            log.info("Skipped - Waittime not yet passed");
            return;
        }

        log.info("Ending TT");
        mTimeLastStableBG = 0;

        TempTarget tempTT = new TempTarget()
                .source(Source.USER)
                .date(DateUtil.roundDateToSec(now()))
                .reason(MainApp.gs(R.string.stable_bg))
                .duration(0)
                .low(0)
                .high(0);
        TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTT);
    }

    private boolean initState(boolean forceLastStatus) {
        mCurrentProfile = ProfileFunctions.getInstance().getProfile();
        mCurrentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();

        if (forceLastStatus || mLastStatus == null) {
            mLastStatus = GlucoseStatus.getGlucoseStatusData(true);
            mCobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
        }

        if (mCurrentProfile != null) {
            mIobTotal = IobCobCalculatorPlugin.getPlugin().calculateFromTreatmentsAndTempsSynchronized(now(), mCurrentProfile);
        }

        return (mCurrentProfile != null);
    }
}
