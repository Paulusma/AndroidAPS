package info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor;

import com.squareup.otto.Subscribe;

import org.apache.commons.math3.exception.InsufficientDataException;
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
import info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor.algorithm.BGCurveFitter;
import info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor.algorithm.ExponentialBGCurveFitter;
import info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor.algorithm.LinearBGCurveFitter;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.general.nsclient.data.NSDeviceStatus;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
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
 * - currently uses a linear polynomial fit
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
        MainApp.bus().register(this);
        super.onStart();

        if (isEnabled(PluginType.GENERAL)) return;

        try {
            // Check after AndroidAPS restart if we had a TT running and sync with that
            synchronized (this) {
                initState(true);
                if (mLastStatus != null) executeCheck();
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
            if (mLastStatus != null) executeCheck();
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

    // TODO: check if hypo time is correct
    private void executeCheck() {
        log.info(">>");
        List<BGLow> detectedLowsAndHypos = new ArrayList<>();
        try {
            if (mLastStatus == null) return;

            // Gather predicted lows & hypo's
            detectedLowsAndHypos.clear();
            gatherPredictions(detectedLowsAndHypos);
            if (SP.getBoolean(R.string.key_hypoppred_algorithm, false))
                gatherAlgoPredictions(detectedLowsAndHypos);

            // Hypo prevention.

            // No detection if 'eating soon' is active
            if (mCurrentTarget != null && mCurrentTarget.reason.startsWith(MainApp.gs(R.string.eatingsoon))) {
                log.info("Skip LOW detection - eating soon active");
            } else {
                BGLow low = impendingLowBGDetected(detectedLowsAndHypos);
                if (hypoTTRunning()) {
                    if (low == null) {
                        endHypoTT();
                    } else
                        log.info("Low found: " + low.getSource() + "@" + low.getLowLevelMins() + " (already running)");
                } else if (low != null) {

                    double hypoTTTargetLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_TT_bg, 0d)
                            , mCurrentProfile.getUnits());
                    int duration = 180; // TT will be cancelled when precondition no longer satisfied

                    if (mCurrentTarget == null || mCurrentTarget.low < hypoTTTargetLevel) {
                        startHypoTT(hypoTTTargetLevel, duration, low);
                    } else if (log.isInfoEnabled()) {
                        log.info("Low found: " + low.getSource() + "@" + low.getLowLevelMins() + " but current TT has higher target");
                    }
                } else
                    log.info("No low (idle)");
            }

            // Hypo alert message
            BGLow hypo = getImminentHypo(detectedLowsAndHypos);
            if (hypo != null) {
                executeHypoAlert(hypo);
            } else
                log.info("No imminent hypo");
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private BGLow impendingLowBGDetected(List<BGLow> detectedLowsAndHypos) {

        // No detection if there are carbs scheduled within the next hour
        List<Treatment> treatments = TreatmentsPlugin.getPlugin().getTreatmentsFromHistory();
        for (Treatment treatment : treatments) {
            if (treatment.isValid
                    && (treatment.date > now() && treatment.date < now() + 60 * 60 * 1000)
                    && treatment.carbs > 0) {
                if (log.isInfoEnabled()) {
                    log.info("Skip detection - imminent treatment found (" + treatment.carbs + "@" + ((treatment.date - now()) / 60 / 1000) + "mins)");
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
            log.info("Skip detection - not wihin configured timeframe");
            return null;
        }

        // No detection if BG below threshold BUT is rising
        double detectionThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , mCurrentProfile.getUnits());
        if (mLastStatus.glucose < detectionThreshold && lastBGTrendIsRising()) {
            log.info("Skip detection - in low but BG rising");
            return null;
        }

        // Currently already below threshold?
        if (mLastStatus.glucose < detectionThreshold) {
            mTimeDetectConditionLastSatisfied = now();
            return new BGLow("ACT", false, 0, 0, BGLow.NOT_FOUND);
        }

        for (BGLow bgLow : detectedLowsAndHypos
        ) {
            if (!bgLow.isHypo() && bgLow.getLowLevelMins() != BGLow.NOT_FOUND) {
                mTimeDetectConditionLastSatisfied = now();
                return bgLow;
            }
        }

        return null;
    }

    private boolean hypoTTRunning() {
        return (mCurrentTarget != null && mCurrentTarget.reason.startsWith(MainApp.gs(R.string.hypo_detection)));
    }

    private void startHypoTT(double target, int duration, BGLow low) {
        log.info("Starting TT");
        TreatmentsPlugin.getPlugin().addToHistoryTempTarget(new TempTarget()
                .date(DateUtil.roundDateToSec(now()))
                .duration(duration)
                .reason(MainApp.gs(R.string.hypo_detection) + " (" + low.getSource() + "@" + low.getLowLevelMins() + ")")
                .source(Source.USER)
                .low(target)
                .high(target));
    }

    private void endHypoTT() {
        long waitTimeMins = SP.getLong(R.string.key_hypoppred_waittime, 0L);
        if (now() < mTimeDetectConditionLastSatisfied + waitTimeMins * 60 * 1000) {
            log.info("Skipped - Waittime not yet passed");
            return;
        }
        mTimeDetectConditionLastSatisfied = 0;

        TempTarget prevTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory(mCurrentTarget.date - 10 * 1000);
        if (prevTarget != null && !prevTarget.reason.startsWith(MainApp.gs(R.string.hypo_detection))) {
            // Previous TT would still be in progress so let it run for the remainder of its original duration
            log.info("Restoring previous TT at " + prevTarget.date);

            TempTarget previousTT = new TempTarget();
            previousTT.copyFrom(prevTarget);
            int minutesRemaining = (int) (previousTT.end() - now()) / 60 / 1000;
            previousTT.date(DateUtil.roundDateToSec(now()))
                    .duration(minutesRemaining)
                    .reason(previousTT.reason + MainApp.gs(R.string.hypopred_continued));
            TreatmentsPlugin.getPlugin().addToHistoryTempTarget(previousTT);
        } else {
            log.info("Ending hypo TT");
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
                long lowLevelMins = BGLow.NOT_FOUND;
                long alertLevelMins = BGLow.NOT_FOUND;
                long lowestBGMins = BGLow.NOT_FOUND;
                double lowestBG = 0.0d;

                long predTimeMins = (prediction.date - baseTime) / 1000 / 60;
                if (prediction.value <= detectionThreshold
                        && predTimeMins < detectionHorizon)
                    lowLevelMins = predTimeMins;
                if (prediction.value <= alertThreshold
                        && predTimeMins < alertHorizon)
                    alertLevelMins = predTimeMins;

                if (lowLevelMins != BGLow.NOT_FOUND || alertLevelMins != BGLow.NOT_FOUND) {
                    lowestBG = prediction.value;
                    lowestBGMins = predTimeMins;
                    while (++i < predictions.size() && sameType(prediction, predictions.get(i))) {
                        BgReading later = predictions.get(i);
                        predTimeMins = (later.date - baseTime) / 1000 / 60;

                        if (lowLevelMins == BGLow.NOT_FOUND
                                && later.value <= detectionThreshold
                                && predTimeMins < detectionHorizon) {
                            lowLevelMins = predTimeMins;
                        }
                        if (alertLevelMins == BGLow.NOT_FOUND
                                && later.value <= alertThreshold
                                && predTimeMins < alertHorizon) {
                            alertLevelMins = predTimeMins;
                        }

                        if (later.value < lowestBG && lowestBGMins < 60) {
                            lowestBG = later.value;
                            lowestBGMins = (later.date - baseTime) / 1000 / 60;
                        }
                    }
                    if (lowLevelMins != BGLow.NOT_FOUND) {
                        BGLow low = new BGLow(getSource(prediction), false, lowLevelMins, lowestBG, lowestBGMins);
                        log.info("Found low: " + low.getSource() + " low@" + low.getLowLevelMins());
                        detectedLowsAndHypos.add(low);
                    }
                    if (alertLevelMins != BGLow.NOT_FOUND) {
                        BGLow hypo = new BGLow(getSource(prediction), true, alertLevelMins, lowestBG, lowestBGMins);
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

        // Determine intersection with LOW line
        double detectionThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , mCurrentProfile.getUnits());
        int detectionHorizon = SP.getInt(R.string.key_hypoppred_horizon, 20);
        long predTimeMins = (long) mBgFit.belowThresholdAt(detectionThreshold, detectionHorizon);
        if (predTimeMins != -1) {
            log.info("Found low (FIT@" + predTimeMins + ")");
            detectedLowsAndHypos.add(new BGLow("FIT", false, predTimeMins, 0.0d, BGLow.NOT_FOUND));
        }

        // Determine intersection with HYPO line
        double alertThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0d)
                , mCurrentProfile.getUnits());
        int alertHorizon = SP.getInt(R.string.key_hypoppred_alert_horizon, 20);
        predTimeMins = (long) mBgFit.belowThresholdAt(alertThreshold, alertHorizon);
        if (predTimeMins != -1) {
            log.info("Found hypo (FIT@" + predTimeMins + ")");
            detectedLowsAndHypos.add(new BGLow("FIT", true, predTimeMins, mBgFit.minimum(predTimeMins, 60), 60));
        }
    }

    // TODO: hypo in progress: sometimes missing carbs even if AAPS BG's used
    // TODO: alarm should be audible even insilent mode (copy from insight?)
    // TODO: accuracy fit?

    private BGLow getImminentHypo(List<BGLow> detectedLowsAndHypos) {

        // Hypo but BG already rising fast => suppress alert
        double hypoAlertLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0.0d),
                mCurrentProfile.getUnits());
        if (mLastStatus.glucose <= hypoAlertLevel && lastBGTrendIsRisingFast()) {
            log.info("Hypo in progress but BG rising fast");
            return null;
        }

        long hypoMins = BGLow.NOT_FOUND;
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
                return new BGLow("ACT", true, 0, firstHypo.getLowestBG(), firstHypo.getLowestBGMins());
            } else {
                log.info("Hypo - ACT (?@?)");
                return new BGLow("ACT", true, 0, 0.0, 0);
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
        if (SP.getLong("nextHypoAlarm", 0L) <= System.currentTimeMillis()) {
            int gramCarbs = 0;
            long inMins = hypo.getLowLevelMins() < 0 ? 0 : hypo.getLowLevelMins();
            if (hypo.getLowestBG() > 0.0d) {
                double hypoAlertLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 0.0d),
                        mCurrentProfile.getUnits());
                double sens = Profile.toMgdl(mCurrentProfile.getIsf(), mCurrentProfile.getUnits());
                gramCarbs = (int) (mCurrentProfile.getIc() * (hypoAlertLevel - hypo.getLowestBG()) / sens); //TODO: autosens corrected?

                // Correct for recent treatment
                if (mCobInfo.displayCob != null)
                    gramCarbs = gramCarbs - mCobInfo.displayCob.intValue();
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

            log.info("Alarm raised.");
        } else
            log.info("Snooze time not passed.");
    }

    /*
        NOTE AAPS trends: > 17.5 DU, 10 to 17.5 SU, 5 to 10 FFU, 5 to -5 FLT, -5 to -10 FFD, -10 to -17.5 SD, < -17.5 DD
     */
    private boolean lastBGTrendIsRising() {
        return (mLastStatus != null
                && (mLastStatus.delta > 5
                && mLastStatus.short_avgdelta > 5));
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
            fitBGCurve(false);
            return true;
        }

        return false;
    }

    private void fitBGCurve(boolean forceNewFit) {
        List<BgReading> bgReadings = IobCobCalculatorPlugin.getPlugin().getBgReadings();

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
            if (predTimeMins > 0) {
                log.info("Fit using exponential function");
                try {
                    mBgFit = mBgFitExp.fit(bgReadings);
                } catch (InsufficientDataException e) {
                    // Skip
                }
            }
        } catch (InsufficientDataException e) {
            // Skip
        }
    }
}
