package info.nightscout.androidaps.plugins.hm.hypopredictor;

import com.squareup.otto.Subscribe;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.exception.InsufficientDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.MainActivity;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.MealData;
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
import info.nightscout.androidaps.plugins.general.overview.dialogs.NewCarbsDialog;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.general.overview.notifications.NotificationWithAction;
import info.nightscout.androidaps.plugins.hm.hypopredictor.algorithm.BGCurveFitter;
import info.nightscout.androidaps.plugins.hm.hypopredictor.algorithm.ExponentialBGCurveFitter;
import info.nightscout.androidaps.plugins.hm.hypopredictor.algorithm.LinearBGCurveFitter;
import info.nightscout.androidaps.plugins.hm.mealadvisor.MealAdvisorPlugin;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.CobInfo;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.events.EventAutosensCalculationFinished;
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
 * - threshold and detection horizon are preferences
 * - optionally uses AAPS BG predictions to determine BG below threshold. IOB prediction is excluded when COB
 * - optionally fits curve to observed BG's for independent LOW detection
 * - optionally detection can be limited to a specific time period (instead of 24h)
 * * tries to prevent LOW by starting a LOW TT
 * - LOW TT level is preference
 * - LOW TT wil run for a certain time after LOW is no longer detected (preference)
 * - does not start  prevention when:
 * 'eating soon' TT is running
 * future carbs present within next hour
 * recent BG is rising again (delta and short_avgdelta both > 5)
 * expected lowest BG > 3*18 mg/dl
 * - LOW TT wil also not be started if currently a TT is running with higher level then LOW TT level
 * - if a TT was running prior to starting LOW TT it is reinstated after LOW TT ends
 * * will alert user if a hypo is imminent
 * - alert BG threshold and detection horizon are preferences
 * - if possible will give an advice on the amount of carbs needed to prevent hypo.
 * - carbs needed depends on lowest hypo BG in next hour, expected and COB (and IC/ISF)
 * - alert will be repeated every 15min for as long as hypo is in progress(/imminent)
 * - alert is suppressed if hypo in progress but BG is already rising fast
 * - alert is suppressed if BG is expected to remain above 3*18 mg/dl
 * * curve fitting algorithm
 * - result is displayed in the overview BG graph
 * - uses linear fit, if LOW imminent refines using exponential fit
 */
public class HypoPredictorPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.HYPOPRED);
    private static HypoPredictorPlugin hypoPredictorPlugin;

    // State information
    private Profile mCurrentProfile = null;
    private TempTarget mCurrentTarget = null;
    private GlucoseStatus mLastStatus = null;
    private CobInfo mCobInfo = null;
    private BGCurveFitter mBgFitLin = new LinearBGCurveFitter(); // Fit of recently observed BG data
    private BGCurveFitter mBgFitExp = new ExponentialBGCurveFitter(); // Fit of recently observed BG data
    private BGCurveFitter mBgFit = null;
    private long mTimeDetectConditionLastSatisfied = 0;
    /*
     * Fitting observed glucose curve
     * */
    private long mLastBGFitted = 0;

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

    public static HypoPredictorPlugin getPlugin() {
        if (hypoPredictorPlugin == null) {
            hypoPredictorPlugin = new HypoPredictorPlugin();
        }

        return hypoPredictorPlugin;
    }

    @Override
    protected void onStart() {
        log.info("");
        MainApp.bus().register(this);
        super.onStart();

        if (!isEnabled(PluginType.GENERAL)) return;

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
        log.info("");
        super.onStop();
        MainApp.bus().unregister(this);
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventAutosensCalculationFinished(final EventAutosensCalculationFinished ev) {
        try {
            if (!isEnabled(PluginType.GENERAL) || !(ev.cause instanceof EventNewBG) || !initState(true))
                return;
            executeCheck();
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventTreatmentChange(final EventTreatmentChange ev) {
        try {
            if (!isEnabled(PluginType.GENERAL) || !initState(false)) return;
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

            if (ev.isChanged(R.string.key_hypoppred_threshold_bg) ||
                    ev.isChanged(R.string.key_hypoppred_24hwindow) ||
                    ev.isChanged(R.string.key_hypoppred_window_to) ||
                    ev.isChanged(R.string.key_hypoppred_window_from) ||
                    ev.isChanged(R.string.key_hypoppred_aaps) ||
                    ev.isChanged(R.string.key_hypoppred_algorithm) ||
                    ev.isChanged(R.string.key_hypoppred_horizon) ||
                    ev.isChanged(R.string.key_hypoppred_waittime)) {
                executeCheck();
            } else if (ev.isChanged(R.string.key_hypoppred_algorithm_steps) ||
                    ev.isChanged(R.string.key_hypoppred_algorithm_weight)) {
                fitBGCurve(true);
                SP.putLong("nextHypoAlarm", System.currentTimeMillis());
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

    /*
        @Subscribe
        public void onEventProfileNeedsUpdate(EventProfileNeedsUpdate ignored) {
            try {
                if (!isEnabled(PluginType.GENERAL) || !initState(false)) return;

                // Convert BG type preference settings to current units
                if (!SP.getString(R.string.key_hypoppred_units, Constants.MMOL).equals(mCurrentProfile.getUnits())) {

                    double bg = SP.getDouble(R.string.key_hypoppred_threshold_bg, 0.0);
                    bg = bg * (Constants.MGDL.equals(mCurrentProfile.getUnits()) ? Constants.MMOLL_TO_MGDL : Constants.MGDL_TO_MMOLL);
                    SP.putDouble(R.string.key_hypoppred_threshold_bg, bg);

                    bg = SP.getDouble(R.string.key_hypoppred_threshold_alert, 0.0);
                    bg = bg * (Constants.MGDL.equals(mCurrentProfile.getUnits()) ? Constants.MMOLL_TO_MGDL : Constants.MGDL_TO_MMOLL);
                    SP.putDouble(R.string.key_hypoppred_threshold_alert, bg);

                    bg = SP.getDouble(R.string.key_hypoppred_TT_bg, 0.0);
                    bg = bg * (Constants.MGDL.equals(mCurrentProfile.getUnits()) ? Constants.MMOLL_TO_MGDL : Constants.MGDL_TO_MMOLL);
                    SP.putDouble(R.string.key_hypoppred_TT_bg, bg);

                    SP.putString(R.string.key_hypoppred_units, mCurrentProfile.getUnits());
                }
            } catch (Exception e) {
                log.error("Unhandled exception", e);
            }
        }
    */
    public List<BgReading> getFittedBGCurve(long fromTime, long toTime) {
        List<BgReading> fit = null;
        try {
            if (!isEnabled(PluginType.GENERAL) || mBgFit == null) return null;
            fit = mBgFit.getBgCurve(fromTime, toTime);
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }

        return fit;
    }

    private void executeCheck() {
        List<BGLow> detectedLowsAndHypos = new ArrayList<>();
        try {
            if (mLastStatus == null) return;

            // Gather predicted LOWs & hypo's
            detectedLowsAndHypos.clear();
            if (SP.getBoolean(R.string.key_hypoppred_aaps, true))
                gatherPredictions(detectedLowsAndHypos);
            if (SP.getBoolean(R.string.key_hypoppred_algorithm, true))
                gatherAlgoPredictions(detectedLowsAndHypos);

            BGLow low = impendingLowBGDetected(detectedLowsAndHypos);
            if (lowTTRunning()) {
                if (low == null) {
                    log.info("No LOW => ending TT");
                    endLowTT();
                } else
                    log.info("LOW but TT already running");
            } else if (low != null) {

                double lowTTTargetLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_TT_bg, 0d)
                        , mCurrentProfile.getUnits());
                int duration = 180; // TT will be cancelled when precondition no longer satisfied

                if (mCurrentTarget == null || (
                        mCurrentTarget.low < lowTTTargetLevel
                        && !mCurrentTarget.reason.startsWith(MainApp.gs(R.string.eatingsoon)))) {
                    log.info("LOW TT replaces current TT");
                    startLowTT(lowTTTargetLevel, duration, low);
                } else if (log.isInfoEnabled()) {
                    log.info("Current TT has higher target or is 'eating soon'");
                }
            }

            // Hypo alert message
            BGLow hypo = getImminentHypo(detectedLowsAndHypos);
            if (hypo != null) {
                executeHypoAlert(hypo);
            } else{
                SP.putLong("hypoStart", now()+60*60*1000); // hypo has not yet started => set to some future time
                log.info("No imminent hypo");
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
            log.info("ERROR: " + e.getMessage());
        }
    }

    private BGLow impendingLowBGDetected(List<BGLow> detectedLowsAndHypos) {

        double detectionThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , mCurrentProfile.getUnits());
        if (mLastStatus.glucose < detectionThreshold) {
            mTimeDetectConditionLastSatisfied = now();
        }

        // No LOW prevention if 'eating soon' is active
        if (mCurrentTarget != null && mCurrentTarget.reason.startsWith(MainApp.gs(R.string.eatingsoon))) {
            log.info("Skip LOW detection - eating soon active");
            return null;
        }

        // No LOW prevention if there are carbs scheduled within the next hour
        if(MealAdvisorPlugin.getPlugin().getScheduledCarbs() > 0){
            log.info("Skip LOW detection - meal scheduled");
            return null;
        }
        List<Treatment> treatments = TreatmentsPlugin.getPlugin().getTreatmentsFromHistory();
        for (Treatment treatment : treatments) {
            if (treatment.isValid
                    && (treatment.date > now() && treatment.date < now() + 60 * 60 * 1000)
                    && treatment.carbs > 0) {
                if (log.isInfoEnabled()) {
                    log.info("Skip LOW detection - imminent treatment found (" + treatment.carbs + "@" + ((treatment.date - now()) / 60 / 1000) + "mins)");
                }
                return null;
            }
        }

        // No LOW prevention if recent BG is rising
        if (bgIsNotDropping()) {
            log.info("Skip LOW detection - BG is rising");
            return null;
        }

        // No LOW prevention if we outside time frame
        boolean useFrame = SP.getBoolean(R.string.key_hypoppred_24hwindow, false);
        long frameStart = SP.getLong(R.string.key_hypoppred_window_from, 0L);
        long frameEnd = SP.getLong(R.string.key_hypoppred_window_to, 0L);
        int time = Profile.secondsFromMidnight() / 60;
        if (useFrame && (time < frameStart || time > frameEnd)) {
            log.info("Skip LOW detection - not wihin configured timeframe");
            return null;
        }

        BGLow firstLow = null;
        for (BGLow bgLow : detectedLowsAndHypos
        ) {
            if (!bgLow.isHypo() && bgLow.getLowLevelMins() != -1) {
                if (bgLow.getLowestBG() < 3 * 18) {
                    if (firstLow == null || (firstLow.getLowLevelMins() > bgLow.getLowLevelMins()))
                        firstLow = bgLow;
                }
            }
        }

        if (firstLow == null)
            log.info("BG not expected to fall below 54");
        else
            log.info("Imminent low reaching " + firstLow.getLowestBG());


        // Currently already below threshold?
        if (mLastStatus.glucose < detectionThreshold) {
            return new BGLow("ACT", false, 0, (firstLow != null) ? firstLow.getLowestBG() : 0.0d,
                    (firstLow != null) ? firstLow.getLowestBGMins() : -1, 0, 0);
        }


        return firstLow;
    }

    private boolean lowTTRunning() {
        boolean running = (mCurrentTarget != null && mCurrentTarget.reason.startsWith(MainApp.gs(R.string.hypo_detection)));
        log.info("TT Running: " + running);

        return running;
    }

    private void startLowTT(double target, int duration, BGLow low) {
        log.info("Starting TT");
        TreatmentsPlugin.getPlugin().addToHistoryTempTarget(new TempTarget()
                .date(DateUtil.roundDateToSec(now()))
                .duration(duration)
                .reason(MainApp.gs(R.string.hypo_detection) + " (" + low.getSource() + "@" + low.getLowLevelMins() + ")")
                .source(Source.USER)
                .low(target)
                .high(target));
    }

    private void endLowTT() {
        long waitTimeMins = SP.getLong(R.string.key_hypoppred_waittime, 0L);
        if (now() < mTimeDetectConditionLastSatisfied + waitTimeMins * 60 * 1000) {
            log.info("End low skipped - Waittime not yet passed");
            return;
        }
        mTimeDetectConditionLastSatisfied = 0;

        TempTarget prevTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory(mCurrentTarget.date - 10 * 1000);
        if (prevTarget != null && !prevTarget.reason.startsWith(MainApp.gs(R.string.hypo_detection))) {
            // Previous TT would still be in progress so let it run for the remainder of its original duration

            TempTarget previousTT = new TempTarget();
            previousTT.copyFrom(prevTarget);
            int minutesRemaining = (int) (previousTT.end() - now()) / 60 / 1000;
            if (minutesRemaining > 0) {
                log.info("Restoring previous TT at " + prevTarget.date);
                previousTT.date(DateUtil.roundDateToSec(now()))
                        .duration(minutesRemaining)
                        .reason(previousTT.reason + MainApp.gs(R.string.hypopred_continued));
                TreatmentsPlugin.getPlugin().addToHistoryTempTarget(previousTT);
                return;
            }
        }

        log.info("Ending LOW TT");
        TempTarget tempTT = new TempTarget()
                .source(Source.USER)
                .date(DateUtil.roundDateToSec(now()))
                .reason(MainApp.gs(R.string.hypo_detection))
                .duration(0)
                .low(0)
                .high(0);
        TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTT);
    }

    // should be in class BgReading
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

    // should be in class BgReading
    private boolean sameType(BgReading bg1, BgReading bg2) {
        return (bg1.isaCOBPrediction && bg2.isaCOBPrediction)
                || (bg1.isCOBPrediction && bg2.isCOBPrediction)
                || (bg1.isIOBPrediction && bg2.isIOBPrediction)
                || (bg1.isUAMPrediction && bg2.isUAMPrediction)
                || (bg1.isZTPrediction && bg2.isZTPrediction);
    }

    private void gatherPredictions(List<BGLow> detectedLowsAndHypos) {
        boolean predictionsAvailable;
        final LoopPlugin.LastRun finalLastRun = LoopPlugin.lastRun;

        int detectionHorizon = SP.getInt(R.string.key_hypoppred_horizon, 20);
        int alertHorizon = SP.getInt(R.string.key_hypoppred_alert_horizon, 20);

        double detectionThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , mCurrentProfile.getUnits());
        double alertThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0d)
                , mCurrentProfile.getUnits());

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
            log.info("Predictions available");
            boolean skipIOB = (mCobInfo.displayCob != null && mCobInfo.displayCob > 0);

            List<BgReading> predictions = apsResult.getPredictions();
            BgReading prediction;
            long baseTime = now();
            for (int i = 0; i < predictions.size(); i++) {
                prediction = predictions.get(i);
                if (prediction.isIOBPrediction && skipIOB) continue;
                long lowLevelMins = -1;
                long alertLevelMins = -1;
                long lowestBGMins;
                double lowestBG;
                double bgAt30Mins = 80, bgAt60Mins = 80;

                long predTimeMins = (prediction.date - baseTime) / 1000 / 60;
                if (prediction.value <= detectionThreshold
                        && predTimeMins < detectionHorizon)
                    lowLevelMins = predTimeMins;
                if (prediction.value <= alertThreshold
                        && predTimeMins < alertHorizon)
                    alertLevelMins = predTimeMins;

                if (lowLevelMins != -1 || alertLevelMins != -1) {
                    lowestBG = prediction.value;
                    lowestBGMins = predTimeMins;
                    while (++i < predictions.size() && sameType(prediction, predictions.get(i))) {
                        BgReading later = predictions.get(i);
                        predTimeMins = (later.date - baseTime) / 1000 / 60;

                        if (lowLevelMins == -1
                                && later.value <= detectionThreshold
                                && predTimeMins < detectionHorizon) {
                            lowLevelMins = predTimeMins;
                        }
                        if (alertLevelMins == -1
                                && later.value <= alertThreshold
                                && predTimeMins < alertHorizon) {
                            alertLevelMins = predTimeMins;
                        }

                        if (later.value < lowestBG) {
                            lowestBG = later.value;
                            lowestBGMins = (later.date - baseTime) / 1000 / 60;
                        }
                        if ((later.date - baseTime) < 30 * 60 * 1000)
                            bgAt30Mins = later.value;
                        if ((later.date - baseTime) < 60 * 60 * 1000)
                            bgAt60Mins = later.value;
                    }
                    if (lowLevelMins != -1) {
                        BGLow low = new BGLow(getSource(prediction), false, lowLevelMins, lowestBG, lowestBGMins, bgAt30Mins, bgAt60Mins);
                        log.info("Found LOW: " + low.getSource() + "@" + low.getLowLevelMins() + "(" + low.getLowestBG() + "@" + low.getLowestBGMins() + ")");
                        detectedLowsAndHypos.add(low);
                    }
                    if (alertLevelMins != -1) {
                        BGLow hypo = new BGLow(getSource(prediction), true, alertLevelMins, lowestBG, lowestBGMins, bgAt30Mins, bgAt60Mins);
                        log.info("Found hypo: " + hypo.getSource() + "@" + hypo.getLowLevelMins() + "(" + hypo.getLowestBG() + "@" + hypo.getLowestBGMins() + ")");
                        detectedLowsAndHypos.add(hypo);
                    }
                    i--;
                }
            }
        } else
            log.info("No predictions available");
    }

    private void gatherAlgoPredictions(List<BGLow> detectedLowsAndHypos) {
        if (mBgFit == null) {
            log.info("No fitted curve yet");
            return;
        }

        // Determine intersection with LOW line.
        // Note regarding time of lowest BG:  since exponential fits are always descending this will be 120 mins. This is not
        // realistic in cases where the fit is very shallow so an error of +/- 4.5 mg/dl in BG is assumed. The effect of this is that
        // for very shallow curves the time of lowest BG will be moved close to 0 while for large drops it will remain close to 120.
        double detectionThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , mCurrentProfile.getUnits());
        int detectionHorizon = SP.getInt(R.string.key_hypoppred_horizon, 20);
        long predTimeMins = (long) mBgFit.belowThresholdAt(detectionThreshold, detectionHorizon);
        if (predTimeMins != -1) {
            double lowestBG = mBgFit.minimum(predTimeMins, 60);
            long timeLowestBG = (long) mBgFit.belowThresholdAt(lowestBG + 4.5, 60);
            log.info("Found LOW (FIT@" + predTimeMins + "(" + lowestBG + "@" + timeLowestBG + ")");
            detectedLowsAndHypos.add(new BGLow("FIT", false, predTimeMins, lowestBG, timeLowestBG, mBgFit.value(30), mBgFit.value(60)));
        }

        // Determine intersection with HYPO line
        double alertThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0d)
                , mCurrentProfile.getUnits());
        int alertHorizon = SP.getInt(R.string.key_hypoppred_alert_horizon, 20);
        predTimeMins = (long) mBgFit.belowThresholdAt(alertThreshold, alertHorizon);
        if (predTimeMins != -1) {
            double lowestBG = mBgFit.minimum(predTimeMins, 60);
            long timeLowestBG = (long) mBgFit.belowThresholdAt(lowestBG + 4.5, 60);
            log.info("Found hypo (FIT@" + predTimeMins + "(" + lowestBG + "@" + timeLowestBG + ")");
            detectedLowsAndHypos.add(new BGLow("FIT", true, predTimeMins, lowestBG, timeLowestBG, mBgFit.value(30), mBgFit.value(60)));
        }
    }

    private BGLow getImminentHypo(List<BGLow> detectedLowsAndHypos) {

        // Hypo but BG already rising fast => suppress alert
        double hypoAlertLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0.0d),
                mCurrentProfile.getUnits());
        if (mLastStatus.glucose <= hypoAlertLevel && bgIsNotDropping()) {
            log.info("Hypo in progress but BG rising");
            return null;
        }

        long hypoMins = 1000;
        BGLow firstHypo = null;
        for (BGLow bgLow : detectedLowsAndHypos
        ) {
            if (bgLow.isHypo() && bgLow.getLowLevelMins() < hypoMins) {
                hypoMins = bgLow.getLowLevelMins();
                firstHypo = bgLow;
            }
        }

        if (mLastStatus.glucose < hypoAlertLevel) {
            // Update if we are already below threshold
            if (firstHypo != null) {
                log.info("Hypo - ACT " + "(" + firstHypo.getLowestBG() + "@" + firstHypo.getLowestBGMins() + ")");
                return new BGLow("ACT", true, 0, firstHypo.getLowestBG(), firstHypo.getLowestBGMins(),firstHypo.getBgAt30Min(),firstHypo.getBgAt60Min());
            } else {
                log.info("Hypo - ACT (?@?)");
                return new BGLow("ACT", true, 0, 0.0, -1,0,0);
            }
        }

        if (log.isInfoEnabled()) {
            log.info("Hypo - " + (firstHypo != null ?
                    firstHypo.getSource() + "@" + firstHypo.getLowLevelMins() + "(" + firstHypo.getLowestBG() + "@" + firstHypo.getLowestBGMins() + ")" : "no hypo"));
        }

        return firstHypo;
    }

    /**
     * Sound alarm. If condition persists alarm will again be sounded in 15 min.
     */
    private void executeHypoAlert(BGLow hypo) {
        long nextHypoAlarm = SP.getLong("nextHypoAlarm", 0L);
        if (nextHypoAlarm <= now()) {

            if(mCobInfo.futureCarbs>0){
                log.info("Skip hypo alert: futurecarbs present");
                return;
            }

            long hypoStart = SP.getLong("hypoStart", now());
            MealData mealData = TreatmentsPlugin.getPlugin().getMealData();
            if(now() < hypoStart && mLastStatus.glucose > 3*18 && Math.ceil(mealData.mealCOB - 0.25*mealData.carbs)>5){
 /* disabled since we now have mealadvisor
               // If enough meal carbs remain issue a warning instead of alert
                Intent alarm = new Intent(MainApp.instance().getApplicationContext(), AlarmSoundService.class);
                alarm.putExtra("soundid", R.raw.didyoueat);
                MainApp.instance().startService(alarm);
   */
                log.info("Hypo but enough COB");
                SP.putLong("nextHypoAlarm", now() + 15 * 60 * 1000);
                return;
            }

            if (nextHypoAlarm < now() - 20 * 60 * 1000) {
                // new hypo
                SP.putLong("hypoStart", now());
                hypoStart = now();
                log.info("Start of hypo");
            }

            double gramCarbs30Min=0,gramCarbs60Min=0;
            long inMins = hypo.getLowLevelMins() < 0 ? 0 : hypo.getLowLevelMins();
            if (hypo.getLowestBGMins() != -1) {
                // Calculate #grams required to get BG back to LOW level
                double lowLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0.0d),
                        mCurrentProfile.getUnits());
                double sens = Profile.toMgdl(mCurrentProfile.getIsf(), mCurrentProfile.getUnits());
                gramCarbs30Min = (mCurrentProfile.getIc() * (lowLevel - hypo.getBgAt30Min()) / sens);
                gramCarbs60Min = (mCurrentProfile.getIc() * (hypo.getBgAt30Min() - hypo.getBgAt60Min()) / sens);
                if (gramCarbs30Min > 0) {
                    log.info("Carbs required before recent carb correction: " + gramCarbs30Min);
                    // Correct for carb intake since start of hypo
                    List<Treatment> treatments = TreatmentsPlugin.getPlugin().getTreatmentsFromHistory();
                    for (Treatment treatment : treatments) {
                        if (treatment.isValid
                                && (treatment.date < now() && treatment.date > hypoStart)
                                && treatment.carbs > 0) {
                            double gramsCOB = Math.max(0, treatment.carbs * (treatment.date + 60 * 60 * 1000 - now()) / (60 * 60 * 1000)); // assume fast acting carbs burn in 1hr
                            log.info("Already took " + treatment.carbs + " grams at " + treatment.date + ", remaining: " + gramsCOB);
                            gramCarbs30Min -= gramsCOB;
                        }
                    }
                    gramCarbs60Min += (gramCarbs30Min<0?gramCarbs30Min:0.0d);
                    log.info("Carbs required: " + gramCarbs30Min+" ("+gramCarbs60Min+")");
                }
            }
            String sMins, sCarbs = "";
            int dextros30Min = (int) Math.ceil(gramCarbs30Min / 2.5);
            int dextros60Min = (int) Math.ceil((gramCarbs30Min+gramCarbs60Min) / 2.5) - dextros30Min;
            if (gramCarbs30Min > 0) {
                sCarbs = MainApp.gs(R.string.hypoppred_alert_msg_carbs, dextros30Min,(dextros60Min>0?dextros60Min:0));
                if (inMins > 0)
                    sMins = MainApp.gs(R.string.hypoppred_alert_msg, inMins);
                else
                    sMins = MainApp.gs(R.string.hypoppred_alert_msg_now, inMins);
                if(MealAdvisorPlugin.getPlugin().isEnabled(PluginType.GENERAL) && MealAdvisorPlugin.getPlugin().getScheduledCarbs()>0){
                    if(MealAdvisorPlugin.getPlugin().getScheduledCarbs()>gramCarbs30Min+gramCarbs60Min) {
                        // Hypo imminent but meal scheduled => start eating right away
                        log.info("Meal " + DateUtil.timeStringSeconds(MealAdvisorPlugin.getPlugin().mealDate()) + " pending but low BG => start meal.");
                        MealAdvisorPlugin.getPlugin().startMeal(R.raw.low_startmeal);
                    }else{
                        // TODO: what if meal doesn't cover expected hypo req... for now: just eat the meal
                        log.info("Meal " + DateUtil.timeStringSeconds(MealAdvisorPlugin.getPlugin().mealDate()) + " pending but low BG => start meal. NOTE: meal doesn't fully cover hypo requirement.");
                        MealAdvisorPlugin.getPlugin().startMeal(R.raw.low_startmeal);
                    }
                }else {
                    NotificationWithAction n = new NotificationWithAction(Notification.HYPO_ALERT, sMins + sCarbs, Notification.URGENT);
                    n.soundId = R.raw.urgentalarm;
                    double ncGrams = (int) gramCarbs30Min;
                    n.action(MainApp.gs(R.string.request), () ->
                            new NewCarbsDialog().setInitialValues(ncGrams, MainApp.gs(R.string.hypopred_corr_note, dextros30Min)).show(MainActivity.instance().getSupportFragmentManager(), "CarbsDialog"));
                    MainApp.bus().post(new EventNewNotification(n));

                    if (SP.getBoolean(R.string.key_ns_create_announcements_from_errors, true)) {
                        NSUpload.uploadError(n.text);
                    }
                }
                log.info("Alarm raised.");
                SP.putLong("nextHypoAlarm", now() + 15 * 60 * 1000);
            } else
                log.info("No alarm raised (no carbs required)");
        } else
            log.info("Snooze time not passed.");
    }

    /*
        NOTE AAPS trends: > 17.5 DU, 10 to 17.5 SU, 5 to 10 FFU, 5 to -5 FLT, -5 to -10 FFD, -10 to -17.5 SD, < -17.5 DD
     */
    private boolean bgIsNotDropping() {
        return (mLastStatus != null
                && (mLastStatus.delta > 0
                && mLastStatus.short_avgdelta > 0));
    }

    private boolean lastBGTrendIsRisingFast() {
        return (mLastStatus != null
                && (mLastStatus.delta > 10
                && mLastStatus.short_avgdelta > 10));
    }

    private boolean initState(boolean forceLastStatus) {
        mCurrentProfile = ProfileFunctions.getInstance().getProfile();
        mCurrentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
        if (forceLastStatus || mLastStatus == null) {
            mLastStatus = GlucoseStatus.getGlucoseStatusData(true);
            mCobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
        }

        if (mCurrentProfile != null) {
            //           SP.putString(R.string.key_hypoppred_units, mCurrentProfile.getUnits());
            fitBGCurve(false);
            return true;
        }

        return false;
    }

    private void fitBGCurve(boolean forceNewFit) {
        List<BgReading> bgReadings = IobCobCalculatorPlugin.getPlugin().getBgReadings();

        if (bgReadings == null || bgReadings.isEmpty()) return;
        BgReading bgr = bgReadings.get(0);
        if (!forceNewFit && bgr.date <= mLastBGFitted) {
            // No new BG readings: we're done
            return;
        }
        mLastBGFitted = bgr.date;

        try {
            mBgFit = mBgFitLin.fit(bgReadings);

            if (!mBgFit.isDescending())
                return;

            double detectionThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                    , mCurrentProfile.getUnits());
            int detectionHorizon = SP.getInt(R.string.key_hypoppred_horizon, 20);
            long predTimeMins = (long) mBgFitLin.belowThresholdAt(detectionThreshold, detectionHorizon);

            // LOW detected within horizon => try a more detailed fit using exponential
            if (predTimeMins >= 0) {
                log.info("Switching to exponential function (linear fit -> LOW in " + predTimeMins + " minutes)");
                try {
                    mBgFit = mBgFitExp.fit(bgReadings);
                    log.info("Fit parameters: " + ArrayUtils.toString(mBgFit.getParms()));
                } catch (InsufficientDataException e) {
                    // Skip
                }
            } else
                log.info("No LOW found in the next " + detectionHorizon + " minutes");
        } catch (InsufficientDataException e) {
            // Skip
        }
    }
}
