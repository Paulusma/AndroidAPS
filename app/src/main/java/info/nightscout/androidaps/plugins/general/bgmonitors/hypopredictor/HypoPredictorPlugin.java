package info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor;

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
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.CobInfo;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;

import static info.nightscout.androidaps.utils.DateUtil.now;

/**
 * Created by Paulusma on 16/07/19.
 * <p>
 * Features:
 * <p>
 * * detects if BG falls below detection threshold in near future
 * - threshold, hypo TT target level, and detection horizon are preferences
 * - uses AAPS BG predictions to determine BG below threshold. IOB prediction is excluded when COB
 * - optionally fits curve to observed BG's for independent hypo detection
 * - optionally detection can be limited to a specific time period (isntead of 24h)
 * * tries to prevent hypo by starting a hypo TT
 * - hypo TT level is preference
 * - hypo TT wil run for a certain time after hypo is no longer detected (preference)
 * - does not start  prevention when 'eating soon' TT is running, or future carbs present within next hour
 * - hypo TT wil also not be started if currently a TT is running with higher level then hypo TT level
 * - if a TT was running prior to starting hypo TT it is reinstated after hypo TT ends
 * * will alert user if a hypo is imminent
 * - alert BG threshold and detection horizon are preferences
 * - if possible will give an advice on the amount of carbs needed to prevent hypo.
 * - carbs needed depends on lowest hypo BG in next hour, expected and COB (and IC/ISF)
 * - alert will be repeated every 15min for as long as hypo is in progress(/imminent)
 * - alert is suppressed if hypo in progress but BG is already rising fast
 * * curve fitting algorithm
 * - result is displayed in the overview BG graph (only when BG is descending) TODO: this should be optional
 * - currently uses a linear polynomial fit TODO: should also use an exponential fit and determin carbs required
 */
public class HypoPredictorPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.HYPOPRED);
    private static HypoPredictorPlugin hypoPredictorPlugin;

    // State information
    private TempTarget runningHypoTT = null;
    private TempTarget previousTT = null;
    private long timeDetectConditionLastSatisfied = 0;
    private GlucoseStatus lastStatus = null;
    private CobInfo cobInfo = null;

    // Fit of observed BG data (linear)
    private PolynomialFunction fittedBGCurve = null;
    private double[] fittedBGCurveParameters = null;

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
        log.debug(">>");
    }

    @Override
    protected void onStart() {
        log.debug(">>");
        MainApp.bus().register(this);
        super.onStart();

        if (isEnabled(PluginType.GENERAL)) {
            log.debug("Disabled");
            return;
        }

        try {
            // Check after AndroidAPS restart if we had a TT running and sync with that
            synchronized (this) {
                getProfile();
                lastStatus = GlucoseStatus.getGlucoseStatusData(true);
                cobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");

                TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
                if (currentTarget != null && currentTarget.reason.startsWith(MainApp.gs(R.string.hypo_detection))) {
                    log.debug("Found running hypo TT, syncing with that");
                    runningHypoTT = currentTarget;
                }
                if (lastStatus != null) {
                    log.debug("Glucose data available");
                    fitBGCurve();
                    executeCheck();
                } else
                    log.debug("NO glucose data available");
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        MainApp.bus().unregister(this);

        log.debug(">>");
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventNewBG(final EventNewBG ev) {
        log.debug(">>");
        if (!isEnabled(PluginType.GENERAL) || !getProfile()) {
            log.debug("Disabled/missing profile");
            return;
        }

        try {
            if (ev.bgReading != null) {
                lastStatus = GlucoseStatus.getGlucoseStatusData(true);
                cobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
                if (lastStatus != null) {
                    log.debug("Glucose data found");
                    fitBGCurve();
                    executeCheck();
                }else
                    log.debug("NO glucose data found");
            } else
                log.debug("NO BG reading...");
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventTreatmentChange(final EventTreatmentChange ev) {
        log.debug(">>");
        if (!isEnabled(PluginType.GENERAL) || !getProfile()) {
            log.debug("Disabled/missing profile");
            return;
        }

        try {
            if (lastStatus == null) {
                lastStatus = GlucoseStatus.getGlucoseStatusData(true);
                cobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
            }
            executeCheck();
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventTempTargetChange(final EventTempTargetChange ev) {
        log.debug(">>");
        if (!isEnabled(PluginType.GENERAL) || !getProfile()) {
            log.debug("Disabled/missing profile");
            return;
        }

        try {
            if (lastStatus == null) {
                lastStatus = GlucoseStatus.getGlucoseStatusData(true);
                cobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
            }

            TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
            if (hypoTTRunning()) {
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
    public synchronized void onEventPreferenceChange(final EventPreferenceChange ev) {
        log.debug(">>");
        if (!isEnabled(PluginType.GENERAL) || !getProfile()) {
            log.debug("Disabled/missing profile");
            return;
        }

        try {
            if (lastStatus == null) {
                lastStatus = GlucoseStatus.getGlucoseStatusData(true);
                cobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
            }

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
            } else if (ev.isChanged(R.string.key_hypoppred_threshold_alert) ||
                    ev.isChanged(R.string.key_hypoppred_alert_horizon)) {
                SP.putLong("nextHypoAlarm", System.currentTimeMillis());
                executeCheck();
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    public List<BgReading> getFittedBGCurve(long fromTime, long toTime) {
        log.debug(">>");
        if (!isEnabled(PluginType.GENERAL) || !getProfile()) {
            log.debug("Disabled");
            return null;
        }

        if (fittedBGCurve == null
                || fittedBGCurveParameters[1] >= 0) { // Only interested in descending line...
            log.debug("curve not fitted yet");
            return null;
        }

        int steps5Min = SP.getInt(R.string.key_hypoppred_algorithm_steps, 12);
        long start = Math.max(fromTime / 1000 - fittedCurveOffset, now() / 1000 - fittedCurveOffset - (steps5Min + 6) * 5 * 60);
        long end = toTime / 1000 - fittedCurveOffset;

        List<BgReading> curve = new ArrayList<>();
        int nSteps = (int) (end - start) / 60;
        for (int i = 0; i <= nSteps; i++) {
            BgReading bg = new BgReading();
            bg.date = (start + i * 60) * 1000 + fittedCurveOffset * 1000;
            bg.value = fittedBGCurve.value(start + i * 60);
            curve.add(bg);
        }

        log.debug("curve available");
        return curve;
    }

    private void executeCheck() {
        try {
            if (lastStatus == null) {
                log.debug("lastStatus == null");
                return;
            }
            detectedLowsAndHypos.clear();
            gatherAAPSHypoPredictions();
            if (SP.getBoolean(R.string.key_hypoppred_algorithm, false)) {
                // Additionaly use independent predictions based on recent BG data
                gatherFittedBGCurveHypoPredictions();
            } else
                log.debug("skipping curve fitting");


            // Hypo prevention.
            BGLow low = impendingLowBGDetected();
            if (hypoTTRunning()) {
                if (low == null) {
                    endHypoTT(true);
                } else
                    log.debug("Low found: " + low.getSource() + "@" + low.getLowLevelMins() + " (already running)");
            } else if (low != null) {

                double hypoTTTargetLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_TT_bg, 0d)
                        , currentProfile.getUnits());
                int duration = 180; // TT will be cancelled when precondition no longer satisfied

                TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
                if (currentTarget == null || currentTarget.low < hypoTTTargetLevel) {
                    startHypoTT(currentTarget, hypoTTTargetLevel, duration, low);
                }else if (log.isDebugEnabled()) {
                    log.debug("Low found: " + low.getSource() + "@" + low.getLowLevelMins() + " but current TT has higher target");
                }
            } else
                log.debug("No low (idle)");


            // Hypo alert message
            BGLow hypo = getImminentHypo();
            if (hypo != null) {
                executeHypoAlert(hypo);
            } else
                log.debug("No imminent hypo");
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private List<BGLow> detectedLowsAndHypos = new ArrayList<>();

    private BGLow impendingLowBGDetected() {

        // No detection if 'eating soon' is active
        TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
        if (currentTarget != null && MainApp.gs(R.string.eatingsoon).equals(currentTarget.reason)) {
            log.debug("Skip detection - eating soon active");
            return null;
        }

        // No detection if there are carbs scheduled within the next hour
        //TODO or use cobInfo.futureCarbs? <- looks beyond 1hr!
        List<Treatment> treatments = TreatmentsPlugin.getPlugin().getTreatmentsFromHistory();
        for (Treatment treatment : treatments) {
            if (treatment.isValid
                    && (treatment.date > now() && treatment.date < now() + 60 * 60 * 1000)
                    && treatment.carbs > 0) {
                if (log.isDebugEnabled()) {
                    log.debug("Skip detection - imminent treatment found (" + treatment.carbs + "@" + ((treatment.date - now()) / 60 / 1000) + "mins)");
                }
                return null;
            }
        }

        // Check if we only guard in specific time frame
        boolean useFrame = SP.getBoolean(R.string.key_hypoppred_24hwindow, false);
        long frameStart = SP.getLong(R.string.key_hypoppred_window_from, 0L);
        long frameEnd = SP.getLong(R.string.key_hypoppred_window_to, 0L);
        int time = Profile.secondsFromMidnight() / 60;
        if (useFrame && (time < frameStart || time > frameEnd)) {
            log.debug("Skip detection - not wihin configured timeframe");
            return null;
        }

        // No detection if BG below threshold BUT is rising
        double detectionThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , currentProfile.getUnits());
        if (lastStatus.glucose < detectionThreshold && lastBGTrendIsRising()) {
            log.debug("Skip detection - in low but BG rising");
            return null;
        }

        // Currently already below threshold?
        if (lastStatus.glucose < detectionThreshold) {
            timeDetectConditionLastSatisfied = now();
            log.debug("Detected low: now");
            return new BGLow("ACT", 0, BGLow.NONE, 0, BGLow.NONE);
        }

        for (BGLow bgLow : detectedLowsAndHypos
        ) {
            if (bgLow.getLowLevelMins() != BGLow.NONE) {
                timeDetectConditionLastSatisfied = now();
                if (log.isDebugEnabled()) {
                    log.debug("Detected low: " + bgLow.getSource() + "@" + bgLow.getLowestBGMins());
                }
                return bgLow;
            }
        }

        log.debug("No low detected");
        return null;
    }

    private boolean hypoTTRunning() {
        log.debug("Hypo running: " + runningHypoTT);
        return (runningHypoTT != null);
    }

    private void startHypoTT(TempTarget currentTarget, double target, int duration, BGLow low) {
        if (currentTarget != null) {
            log.debug("Saving current TT started at: " + currentTarget.date);
            previousTT = new TempTarget();
            previousTT.copyFrom(currentTarget);
        }

        String reason = " (" + low.getSource() + "@" + low.getLowLevelMins() + ")";
        runningHypoTT = new TempTarget()
                .date(DateUtil.roundDateToSec(now()))
                .duration(duration)
                .reason(MainApp.gs(R.string.hypo_detection) + reason)
                .source(Source.USER)
                .low(target)
                .high(target);

        log.debug("Starting TT");
        TreatmentsPlugin.getPlugin().addToHistoryTempTarget(runningHypoTT);
    }

    private void endHypoTT(boolean wait) {
        log.debug("Wait check: " + wait);
        if (wait) {
            long waitTimeMins = SP.getLong(R.string.key_hypoppred_waittime, 0L);
            if (now() < timeDetectConditionLastSatisfied + waitTimeMins * 60 * 1000) {
                log.debug("Skipped - Waittime not yet passed");
                return;
            }

            if (previousTT != null && previousTT.isInProgress()) {
                // Previous TT would still be in progress so let it run for the remainder of its original duration
                log.debug("Restoring previous TT at " + previousTT.date);
                int minutesRemaining = (int) (previousTT.end() - now()) / 60 / 1000;
                previousTT.date(DateUtil.roundDateToSec(now()))
                        .duration(minutesRemaining)
                        .reason(previousTT.reason + R.string.hypopred_continued);
                TreatmentsPlugin.getPlugin().addToHistoryTempTarget(previousTT);
            } else {
                log.debug("Ending hypo TT");
                TempTarget tempTT = new TempTarget()
                        .source(Source.USER)
                        .date(DateUtil.roundDateToSec(now()))
                        .reason(MainApp.gs(R.string.hypo_detection))
                        .duration(0)
                        .low(0)
                        .high(0);
                TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTT);
            }
        }

        // Also reset plugin state to 'idle'
        log.debug("Reset state to idle");
        runningHypoTT = null;
        previousTT = null;
        timeDetectConditionLastSatisfied = 0;
    }

    private void gatherAAPSHypoPredictions() {
        boolean predictionsAvailable;
        final LoopPlugin.LastRun finalLastRun = LoopPlugin.lastRun;

        int detectionHorizon = SP.getInt(R.string.key_hypoppred_horizon, 20);
        int alertHorizon = SP.getInt(R.string.key_hypoppred_alert_horizon, 20);

        double detectionThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , currentProfile.getUnits());
        double alertThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0d)
                , currentProfile.getUnits());

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
            log.debug("Predictions available");
            boolean skipIOB = (cobInfo.displayCob != null && cobInfo.displayCob > 0);

            List<BgReading> predictions = apsResult.getPredictions();
            BgReading prediction;
            long baseTime = now();
            for (int i = 0; i < predictions.size(); i++) {
                prediction = predictions.get(i);
                if (prediction.isIOBPrediction && skipIOB) continue;
                long lowLevelMins = BGLow.NONE;
                long alertLevelMins = BGLow.NONE;
                long lowestBGMins = BGLow.NONE;
                double lowestBG = 0.0d;

                long predTimeMins = (prediction.date - baseTime) / 1000 / 60;
                if (prediction.value <= detectionThreshold
                        && predTimeMins < detectionHorizon)
                    lowLevelMins = predTimeMins;
                if (prediction.value <= alertThreshold
                        && predTimeMins < alertHorizon)
                    alertLevelMins = predTimeMins;

                if (lowLevelMins != BGLow.NONE || alertLevelMins != BGLow.NONE) {
                    // find minimum for the type of prediction, in the next hour. This will be used to determine amount of carbs to eat. TODO is this correct?
                    lowestBG = prediction.value;
                    lowestBGMins = predTimeMins;
                    while (++i < predictions.size() && sameType(prediction, predictions.get(i))) {
                        BgReading later = predictions.get(i);
                        predTimeMins = (later.date - baseTime) / 1000 / 60;

                        if (lowLevelMins == BGLow.NONE
                                && later.value <= detectionThreshold
                                && predTimeMins < detectionHorizon) {
                            lowLevelMins = predTimeMins;
                        }
                        if (alertLevelMins == BGLow.NONE
                                && later.value <= alertThreshold
                                && predTimeMins < alertHorizon) {
                            alertLevelMins = predTimeMins;
                        }

                        if (later.value < lowestBG && lowestBGMins < 60) {
                            lowestBG = later.value;
                            lowestBGMins = (later.date - baseTime) / 1000 / 60;
                        }
                    }
                    BGLow low = new BGLow(getSource(prediction), lowLevelMins, alertLevelMins, lowestBG, lowestBGMins);
                    if (log.isDebugEnabled()) {
                        log.debug("Found low: " + low.getSource()
                                + " " + (low.getLowLevelMins() != BGLow.NONE ? "low@" + low.getLowLevelMins() : "")
                                + " " + (low.getAlertLevelMins() != BGLow.NONE ? "hypo@" + low.getAlertLevelMins() + "(" + low.getLowestBG() + "@" + low.getLowestBGMins() + ")" : ""));
                    }
                    detectedLowsAndHypos.add(low);
                    i--;
                }
            }
        } else
            log.debug("No predictions available");
    }

    // TODO: should be in class BgReading
    private String getSource(BgReading prediction) {
        if (prediction.isIOBPrediction)
            return "IOB";
        if (prediction.isCOBPrediction)
            return "COB";
        if (prediction.isaCOBPrediction)
            return "aCOB";
        if (prediction.isUAMPrediction)
            return "UAM";
        if (prediction.isZTPrediction)
            return "ZT";
        return "N/A";
    }

    // TODO: should be in class BgReading
    private boolean sameType(BgReading bg1, BgReading bg2) {
        if ((bg1.isaCOBPrediction && bg2.isaCOBPrediction)
                || (bg1.isCOBPrediction && bg2.isCOBPrediction)
                || (bg1.isIOBPrediction && bg2.isIOBPrediction)
                || (bg1.isUAMPrediction && bg2.isUAMPrediction)
                || (bg1.isZTPrediction && bg2.isZTPrediction))
            return true;
        return false;
    }

    /*
     * Hypo detection algorithm using curve fitting
     * */
    private long fittedCurveOffset = 0;

    private void fitBGCurve() {
        double weightFactor = SP.getDouble(R.string.key_hypoppred_algorithm_weight, 0.95d);
        int steps5Min = SP.getInt(R.string.key_hypoppred_algorithm_steps, 12);

        // Get recent observations
        IobCobCalculatorPlugin iobCobCalculatorPlugin = IobCobCalculatorPlugin.getPlugin();
        List<BgReading> bgReadingsArray = iobCobCalculatorPlugin.getBgReadings();

        if (bgReadingsArray == null || bgReadingsArray.size() == 0) {
            log.debug("No readings!");
            return;
        }

        // All times are in secs relative to calculation (=current) time
        fittedCurveOffset = now() / 1000;
        long start = -steps5Min * 5 * 60;

        BgReading bgr;
        WeightedObservedPoints obs = new WeightedObservedPoints();
        for (int i = 0; i < bgReadingsArray.size(); i++) {
            bgr = bgReadingsArray.get(i);
            long time = bgr.date / 1000 - fittedCurveOffset;
            if (time <= start) continue;
            int pow = (int) (-time / (60 * 5d));
            double weight = Math.pow(weightFactor, pow);
            obs.add(weight, time, bgr.value);
        }
        List lObs = obs.toList();
        if (lObs.size() < 3) return;

        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(1);
        fittedBGCurveParameters = fitter.fit(lObs);
        fittedBGCurve = new PolynomialFunction(fittedBGCurveParameters);

        //TODO: if BG descending then fit using logistic curve. Parameters from linear curve
        log.debug("Curve fitted!");
    }

    private void gatherFittedBGCurveHypoPredictions() {
        if (fittedBGCurve == null) {
            log.debug("No fitted curve yet");
            return;
        }

        // Determine intersection with LOW line and polycurve = a*t+b
        double detectionThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , currentProfile.getUnits());
        int detectionHorizon = SP.getInt(R.string.key_hypoppred_horizon, 20);
        double a = fittedBGCurveParameters[1];
        double b = fittedBGCurveParameters[0] - detectionThreshold;
        if (a < 0) {
            long predTimeMins = (long) (-b / a / 60);
            if (predTimeMins <= 0)
                if (lastStatus.glucose > detectionThreshold) {
                    // Predicted we should already be in a low but actually not yet: expect it within 5 mins
                    log.debug("Found low: FIT@5");
                    detectedLowsAndHypos.add(new BGLow("FIT", 5, BGLow.NONE, 0.0d, BGLow.NONE));
                } else {
                    log.debug("Found low: in progress");
                    detectedLowsAndHypos.add(new BGLow("ACT", 0, BGLow.NONE, 0.0d, BGLow.NONE));
                }
            else if (predTimeMins <= detectionHorizon) {
                // not reaching thresholdBG
                log.debug("Found low: FIT@" + predTimeMins);
                detectedLowsAndHypos.add(new BGLow("FIT", predTimeMins, BGLow.NONE, 0.0d, BGLow.NONE));
            }
        }

        // Determine intersection with HYPO line and polycurve = a*t+b
        double alertThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0d)
                , currentProfile.getUnits());
        int alertHorizon = SP.getInt(R.string.key_hypoppred_alert_horizon, 20);

        a = fittedBGCurveParameters[1];
        b = fittedBGCurveParameters[0] - alertThreshold;
        if (a < 0) {
            long predTimeMins = (long) (-b / a / 60);
            if (predTimeMins <= 0) {
                if (lastStatus.glucose > alertThreshold) {
                    // Predicted we should already be in a hypo but actually not yet: expect it within 5 mins
                    log.debug("Found hypo: FIT@5");
                    detectedLowsAndHypos.add(new BGLow("FIT", BGLow.NONE, 5, 0.0d, BGLow.NONE));
                } else {
                    log.debug("Found hypo: in progress");
                    detectedLowsAndHypos.add(new BGLow("FIT", BGLow.NONE, 0, 0.0d, BGLow.NONE));
                }
            } else if (predTimeMins <= alertHorizon) {
                // not reaching thresholdBG
                log.debug("Found hypo: FIT@" + predTimeMins);
                detectedLowsAndHypos.add(new BGLow("FIT", BGLow.NONE, predTimeMins, 0.0d, BGLow.NONE));
            }
        }
    }

    /**
     * Sound alarm. If condition persists alarm will again be sounded in 15 min.
     */

    private BGLow getImminentHypo() {

        // Hypo but BG already rising fast => suppress alert
        double hypoAlertLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0.0d),
                currentProfile.getUnits());
        if (lastStatus.glucose <= hypoAlertLevel && lastBGTrendIsRisingFast()) {
            log.debug("Hypo in progress but BG rising fast");
            return null;
        }

        long alertMins = BGLow.NONE;
        BGLow firstHypo = null;
        for (BGLow bgLow : detectedLowsAndHypos
        ) {
            if (bgLow.getAlertLevelMins() < alertMins) {
                alertMins = bgLow.getAlertLevelMins();
                firstHypo = bgLow;
            }
        }

        if (lastStatus.glucose < hypoAlertLevel) {
            // Update if we are already below threshold
            if (firstHypo != null && firstHypo.getAlertLevelMins() < 6) {
                if (log.isDebugEnabled()) {
                    log.debug("Hypo - ACT " + "(" + firstHypo.getLowestBG() + "@" + firstHypo.getLowestBGMins());
                }
                return new BGLow("ACT", BGLow.NONE, 0, firstHypo.getLowestBG(), firstHypo.getLowestBGMins());
            } else {
                log.debug("Hypo - ACT (0@0)");
                return new BGLow("ACT", BGLow.NONE, 0, 0.0d, BGLow.NONE);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Hypo - " + (firstHypo != null ?
                    firstHypo.getSource() + "@" + firstHypo.getAlertLevelMins() + "(" + firstHypo.getLowestBG() + "@" + firstHypo.getLowestBGMins() + ")" : "no hypo"));
        }

        return firstHypo;
    }

    //TODO: hypo in progress: sometimes missing carbs even if AAPS BG's used
    // TODO: alarm should be audible even insilent mode (copy from insight?)
    private void executeHypoAlert(BGLow hypo) {
        if (SP.getLong("nextHypoAlarm", 0L) <= System.currentTimeMillis()) {
            int gramCarbs = 0;
            long inMins = hypo.getAlertLevelMins() < 0 ? 0 : hypo.getAlertLevelMins();
            if (hypo.getLowestBG() > 0.0d) {
                double hypoAlertLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0.0d),
                        currentProfile.getUnits());
                double sens = Profile.toMgdl(currentProfile.getIsf(), currentProfile.getUnits());
                gramCarbs = (int) (currentProfile.getIc() * (hypoAlertLevel - hypo.getLowestBG()) / sens); //TODO: autosens corrected?

                // Correct for recent treatment
                gramCarbs = gramCarbs - cobInfo.displayCob.intValue();
            }
            Notification n;
            String sMins, sCarbs = "";
            if (gramCarbs > 0)
                sCarbs = MainApp.gs(R.string.hypoppred_alert_msg_carbs, gramCarbs);
            if (inMins > 0)
                sMins = MainApp.gs(R.string.hypoppred_alert_msg, inMins);
            else
                sMins = MainApp.gs(R.string.hypoppred_alert_msg_now, inMins);
            n = new Notification(Notification.HYPO_ALERT, sMins + sCarbs, Notification.URGENT);
            n.soundId = R.raw.urgentalarm;
            SP.putLong("nextHypoAlarm", System.currentTimeMillis() + 15 * 60 * 1000);
            MainApp.bus().post(new EventNewNotification(n));
            if (SP.getBoolean(R.string.key_ns_create_announcements_from_errors, true)) {
                NSUpload.uploadError(n.text);
            }
            log.debug("Alarm raised.");
        } else
            log.debug("Snooze time not passed.");
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

    private boolean getProfile() {
        currentProfile = ProfileFunctions.getInstance().getProfile();
        return (currentProfile != null);
    }

}
