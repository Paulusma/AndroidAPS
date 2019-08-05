package info.nightscout.androidaps.plugins.general.monitors;

import com.squareup.otto.Subscribe;

import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.events.EventReloadProfileSwitchData;
import info.nightscout.androidaps.events.EventTempTargetChange;
import info.nightscout.androidaps.events.EventTreatmentChange;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.aps.loop.APSResult;
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.general.nsclient.data.NSDeviceStatus;
import info.nightscout.androidaps.plugins.general.overview.OverviewPlugin;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;

import static info.nightscout.androidaps.utils.DateUtil.now;

/**
 * Created by Paulusma on 16/07/19.
 */
public class HypoPredictorPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.HYPOPRED);
    private static HypoPredictorPlugin hypoPredictorPlugin;

    // State information
    private TempTarget runningHypoTT = null;
    private TempTarget previousTT = null;
    private long timePCLastSatisfied = 0;
    private GlucoseStatus lastStatus = null;

    // Fit of observed BG data (linear)
    private PolynomialFunction gcPolyCurve = null;
    private double[] gcPolyCoeff = null;

    private Profile currentProfile = null;

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

        // Check after AndroidAPS restart if we had a TT running and sync with that
        if (isEnabled(PluginType.GENERAL)) {
            synchronized (this) {
                currentProfile = ProfileFunctions.getInstance().getProfile();
                lastStatus = GlucoseStatus.getGlucoseStatusData(true);

                TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
                if (currentTarget != null && currentTarget.reason.equals(MainApp.gs(R.string.hypo_detection))) {
                    runningHypoTT = currentTarget;
                    timePCLastSatisfied = now();
                }
                if (lastStatus != null){
                    fitBGCurve();
                    executeCheck();
                    checkHypoAlert();
                }
            }
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
        if (!isEnabled(PluginType.GENERAL))
            return;
        try {
            if (ev.bgReading != null) {
                lastStatus = GlucoseStatus.getGlucoseStatusData(true);
                if (lastStatus != null) {
                    fitBGCurve();
                    executeCheck();
                    checkHypoAlert();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventTreatmentChange(final EventTreatmentChange ev) {
        if (!isEnabled(PluginType.GENERAL))
            return;
        if (lastStatus == null)
            lastStatus = GlucoseStatus.getGlucoseStatusData(true);

        try {
            // If carbs are scheduled hypo TT is suppressed
            executeCheck();
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventTempTargetChange(final EventTempTargetChange ev) {
        if (!isEnabled(PluginType.GENERAL))
            return;
        if (lastStatus == null)
            lastStatus = GlucoseStatus.getGlucoseStatusData(true);

        try {
            TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
            if (pluginIsRunning()) {
                if (currentTarget != null) {
                    if (runningHypoTT.date != currentTarget.date) {
                        endHypoTT(false);
                        executeCheck();
                    }
                } else {
                    // don't allow manual cancellation of our TT
                    endHypoTT(false); // sync
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
        if (!isEnabled(PluginType.GENERAL))
            return;
        if (lastStatus == null)
            lastStatus = GlucoseStatus.getGlucoseStatusData(true);

        try {
            if (ev.isChanged(R.string.key_hypoppred_threshold_bg) ||
                    ev.isChanged(R.string.key_hypoppred_24hwindow) ||
                    ev.isChanged(R.string.key_hypoppred_window_to) ||
                    ev.isChanged(R.string.key_hypoppred_window_from) ||
                    ev.isChanged(R.string.key_hypoppred_algorithm) ||
                    ev.isChanged(R.string.key_hypoppred_horizon) ||
                    ev.isChanged(R.string.key_hypoppred_waittime)) {

                // Sync state with changed preferences
                executeCheck();
            } else if (ev.isChanged(R.string.key_hypoppred_algorithm_steps) ||
                    ev.isChanged(R.string.key_hypoppred_algorithm_weight)) {
                fitBGCurve();
                executeCheck();
            } else if (ev.isChanged(R.string.key_hypoppred_threshold_alert)) {
                SP.putLong("nextHypoAlarm", System.currentTimeMillis());
                checkHypoAlert();
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private void executeCheck() {
        try {
            if (lastStatus == null)
                return;

            if (pluginIsRunning()) {
                if (!preConditionSatisfied()) {
                    endHypoTT(true);
                }
            } else if (preConditionSatisfied()) {
                if (currentProfile == null) {
                    return;
                }
                double target = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_TT_bg, 0d)
                        , currentProfile.getUnits());
                int duration = 180; // TT will be cancelled when precondition no longer satisfied

                TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
                if (currentTarget == null || currentTarget.low < target)
                    startHypoTT(currentTarget, target, duration);
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private boolean preConditionSatisfied() {
        if (lastStatus == null)
            return false;

        if (currentProfile == null) {
            return false;
        }

        // Check if we only guard in specific time frame
        boolean useFrame = SP.getBoolean(R.string.key_hypoppred_24hwindow, false);
        long frameStart = SP.getLong(R.string.key_hypoppred_window_from, 0L);
        long frameEnd = SP.getLong(R.string.key_hypoppred_window_to, 0L);
        int time = Profile.secondsFromMidnight() / 60;

        if (!useFrame || (time >= frameStart && time < frameEnd)) {
            return false;
        }

        // Never TT if BG above LOW level and rising
        double threshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                        , currentProfile.getUnits());
        if (lastStatus.glucose > threshold && lastBGTrendIsRising())
            return false;

        // Do not check if 'eating soon' is active
        TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
        if (currentTarget != null && MainApp.gs(R.string.eatingsoon).equals(currentTarget.reason))
            return false;

        // Do not check if there are carbs scheduled within the next hour
        List<Treatment> treatments = TreatmentsPlugin.getPlugin().getTreatmentsFromHistory();
        for (Treatment treatment : treatments) {
            if (treatment.isValid
                    && (treatment.date > now() && treatment.date < now() + 60 * 60 * 1000)
                    && treatment.carbs > 0) {
                return false;
            }
        }

        // Check AAPS BG predictions
        if (checkAPSPredictions()) {
            timePCLastSatisfied = now();
            return true;
        }

        // If not check if hypo is expected using polynomal fit
        if (SP.getBoolean(R.string.key_hypoppred_algorithm, false)) {
            if (checkHypoUsingBGCurveFit()) {
                timePCLastSatisfied = now();
                return true;
            }
        }

        return false;
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

    private void endHypoTT(boolean wait) {
        if (wait) {
            long waitTimeMins = SP.getLong(R.string.key_hypoppred_waittime, 0L);
            if (now() < timePCLastSatisfied + waitTimeMins * 60 * 1000)
                return;

            if (previousTT != null && previousTT.isInProgress()) {
                // Previous TT would still be in progress so let it run for the remainder of its original duration
                int minutesRemaining = (int) (previousTT.end() - now()) / 60 / 1000;
                previousTT.date(DateUtil.roundDateToSec(now()))
                        .duration(minutesRemaining)
                        .reason(previousTT.reason + R.string.hypopred_continued);
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

    private boolean checkAPSPredictions() {
        boolean predictionsAvailable;
        final LoopPlugin.LastRun finalLastRun = LoopPlugin.lastRun;

        int hypoHorizon = SP.getInt(R.string.key_hypoppred_horizon, 60);

        if (currentProfile == null) {
            return false;
        }

        double bgThreshold = OverviewPlugin.bgTargetLow;

        if (Config.APS)
            predictionsAvailable = finalLastRun != null && finalLastRun.request.hasPredictions;
        else
            predictionsAvailable = Config.NSCLIENT;

        APSResult apsResult = null;
        if (predictionsAvailable) {
            if (Config.APS)
                apsResult = finalLastRun.constraintsProcessed;
            else
                apsResult = NSDeviceStatus.getAPSResult();
        }
        if (apsResult != null) {
            boolean noCarbsOnBoard = true;
            boolean iobLow = false;
            List<BgReading> predictions = apsResult.getPredictions();
            for (BgReading prediction : predictions
            ) {
                noCarbsOnBoard = noCarbsOnBoard && !prediction.isCOBPrediction
                        && !prediction.isaCOBPrediction && !prediction.isUAMPrediction;
                if (prediction.value <= bgThreshold
                        && prediction.date < now() + hypoHorizon * 60 * 1000) {

                    if (!prediction.isIOBPrediction)
                        return true;
                    else
                        iobLow = true;
                }
            }

            // IOB only reliable if no cargs on board, ie if COB not calculated
            return (iobLow && noCarbsOnBoard);
        }

        return false;
    }

    /*
     * Hypo detection algorithm using curve fitting
     * */
    private long gcCurveOffset = 0;

    private void fitBGCurve() {
        double weightFactor = SP.getDouble(R.string.key_hypoppred_algorithm_weight, 0.95d);
        int steps5Min = SP.getInt(R.string.key_hypoppred_algorithm_steps, 12);

        // Get recent observations
        IobCobCalculatorPlugin iobCobCalculatorPlugin = IobCobCalculatorPlugin.getPlugin();
        List<BgReading> bgReadingsArray = iobCobCalculatorPlugin.getBgReadings();

        if (bgReadingsArray == null || bgReadingsArray.size() == 0) {
            return;
        }

        // All times are in secs relative to calculation (=current) time
        gcCurveOffset = now() / 1000;
        long start = -steps5Min * 5 * 60;

        BgReading bgr;
        WeightedObservedPoints obs = new WeightedObservedPoints();
        for (int i = 0; i < bgReadingsArray.size(); i++) {
            bgr = bgReadingsArray.get(i);
            long time = bgr.date / 1000 - gcCurveOffset;
            if (time <= start) continue;
            int pow = (int) (-time / (60 * 5d));
            double weight = Math.pow(weightFactor, pow);
            obs.add(weight, time, bgr.value);
        }
        List lObs = obs.toList();
        if (lObs.size() < 3) return;

        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(1);
        gcPolyCoeff = fitter.fit(lObs);
        gcPolyCurve = new PolynomialFunction(gcPolyCoeff);

        //TODO: if BG descending then fit using logistic curve. Parameters from linear curve
    }

    private boolean checkHypoUsingBGCurveFit() {
        if (gcPolyCurve == null) {
            return false;
        }

        if (currentProfile == null) {
            return false;
        }

        double hypoBG = OverviewPlugin.bgTargetLow;
        int hypoHorizon = SP.getInt(R.string.key_hypoppred_horizon, 60);

        long horizonSec = hypoHorizon * 60;

        // Determine intersection with LOW line polycurve = a*t+b
        double a = gcPolyCoeff[1];
        double b = gcPolyCoeff[0] - hypoBG;
        if (a != 0) {
            double t1 = -b / a;
            return (t1 >= 0 && t1 <= horizonSec);
        } else {
            // not reaching thresholdBG
            return (gcPolyCurve.value(0) < 0);
        }
    }

    public List<BgReading> getFittedPolyCurve(long fromTime, long toTime) {
        if (gcPolyCurve == null
                || !isEnabled(PluginType.GENERAL)
                || gcPolyCoeff[1] >= 0) // Only interested in descending line...
            return null;

        int steps5Min = SP.getInt(R.string.key_hypoppred_algorithm_steps, 12);
        long start = Math.max(fromTime / 1000 - gcCurveOffset, now() / 1000 - gcCurveOffset - (steps5Min + 6) * 5 * 60);
        long end = toTime / 1000 - gcCurveOffset;

        List<BgReading> curve = new ArrayList<>();
        int nSteps = (int) (end - start) / 60;
        for (int i = 0; i <= nSteps; i++) {
            BgReading bg = new BgReading();
            bg.date = (start + i * 60) * 1000 + gcCurveOffset * 1000;
            bg.value = gcPolyCurve.value(start + i * 60);
            curve.add(bg);
        }

        return curve;
    }

    /**
     * Sound alarm. If condition persists alarm will again be sounded in 15 min.
     */

    private void checkHypoAlert() {
        try {
            if (currentProfile == null) {
                return;
            }

            if (lastStatus == null)
                return;

            double hypoAlertLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 3.5d),
                    currentProfile.getUnits());

            if (lastStatus.glucose < hypoAlertLevel && !lastBGTrendIsRisingFast()) {
                startHypoAlert(0, 0);
            }

        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private void startHypoAlert(int inMinutes, int gramCarbs) {
        if (SP.getLong("nextHypoAlarm", 0L) <= System.currentTimeMillis()) {
            Notification n = new Notification(Notification.HYPO_ALERT, MainApp.gs(R.string.hypoppred_alert_msg, inMinutes, gramCarbs), Notification.URGENT);
            n.soundId = R.raw.urgentalarm;
            SP.putLong("nextHypoAlarm", System.currentTimeMillis() + 15 * 60 * 1000);
            MainApp.bus().post(new EventNewNotification(n));
            if (SP.getBoolean(R.string.key_ns_create_announcements_from_errors, true)) {
                NSUpload.uploadError(n.text);
            }
        }
    }

    /*
        NOTE AAPS trends: > 17.5 DU, 10 to 17.5 SU, 5 to 10 FFU, 5 to -5 FLT, -5 to -10 FFD, -10 to -17.5 SD, < -17.5 DD
     */
    private boolean lastBGTrendIsRising() {
        return (lastStatus != null
                && (lastStatus.delta > 5
                && lastStatus.short_avgdelta > 5));
    }

    private boolean lastBGTrendIsRisingFast() {
        return (lastStatus != null
                && (lastStatus.delta > 10
                && lastStatus.short_avgdelta > 10));
    }

    private class Hypo{
        public Double getDate() {
            return date;
        }

        public Double getLowestBG() {
            return lowestBG;
        }

        public Double getLowestDate() {
            return lowestDate;
        }

        private Double date;
        private Double lowestBG;
        private Double lowestDate;

        Hypo(Double _date){
            date = _date;
        }

        Hypo(Double _date, double _lowestBG, Double _lowestDate){
            date = _date;
            lowestBG = _lowestBG;
            lowestDate = _lowestDate;
        }
    }

}
