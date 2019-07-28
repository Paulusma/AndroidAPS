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
import info.nightscout.androidaps.data.MealData;
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
import info.nightscout.androidaps.plugins.aps.loop.APSResult;
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.nsclient.data.NSDeviceStatus;
import info.nightscout.androidaps.plugins.general.overview.OverviewPlugin;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.DefaultValueHelper;
import info.nightscout.androidaps.utils.MidnightTime;
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

        // Check after AndroidAPS restart if we had a TT running and sync with that
        if (isEnabled(PluginType.GENERAL)) {
            synchronized (this) {
                TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
                if (currentTarget != null && currentTarget.reason.equals(MainApp.gs(R.string.hypo_detection))) {
                    runningHypoTT = currentTarget;
                    timePCLastSatisfied = now();
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
            if (this != getPlugin()) {
                if (L.isEnabled(L.AUTOSENS))
                    log.debug("Ignoring event for non default instance");
                return;
            }
            if (ev.bgReading != null) {
                bgPolynomalFit();
                executeCheck(ev.bgReading.value);
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventTempTargetChange(final EventTempTargetChange ev) {
        if (!isEnabled(PluginType.GENERAL))
            return;
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
            } else {
                executeCheck(0);
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventPreferenceChange(final EventPreferenceChange ev) {
        if (!isEnabled(PluginType.GENERAL))
            return;
        try {
            if (ev.isChanged(R.string.key_hypoppred_threshold_bg) ||
                    ev.isChanged(R.string.key_hypoppred_24hwindow) ||
                    ev.isChanged(R.string.key_hypoppred_window_to) ||
                    ev.isChanged(R.string.key_hypoppred_window_from) ||
                    ev.isChanged(R.string.key_hypoppred_algorithm) ||
                    ev.isChanged(R.string.key_hypoppred_algorithm_horizon)) {

                // Sync state with changed preferences
                executeCheck(0);
            } else if (ev.isChanged(R.string.key_hypoppred_algorithm_steps) ||
                    ev.isChanged(R.string.key_hypoppred_algorithm_weight) ) {
                bgPolynomalFit();
                executeCheck(0);
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    // Threadsafe to prevent checks on inconsistent state
    private void executeCheck(double lastBG) {
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
            } else if (preConditionSatisfied(lastBG)) {
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

    private boolean preConditionSatisfied(double lastBG) {
        final Profile currentProfile = ProfileFunctions.getInstance().getProfile();
        if (currentProfile == null) {
            return false;
        }

        // Do not check if 'eating soon' is active
        TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
        if (currentTarget != null && MainApp.gs(R.string.eatingsoon).equals(currentTarget.reason))
            return false;

        // Do not check if there are carbs scheduled within the next hour
        List<Treatment>  treatments = TreatmentsPlugin.getPlugin().getTreatmentsFromHistory();
        for (Treatment treatment:treatments){
            if(treatment.isValid
                    && (treatment.date > now() && treatment.date < now()+60*60*1000)
                    && treatment.carbs > 0 ){
                return false;
            }
        }

        // Check AAPS BG predictions
        if (checkAPSPredictions()) {
            return true;
        }

        // If not check if hypo is expected using polynomal fit
        if (SP.getBoolean(R.string.key_hypoppred_algorithm, false)) {
            if (checkHypoUsingPolynomalFit())
                return true;
        }

        // Lastly check if condition 'BG lower then threshold' applies
        // This is done last to give predictors a chance to warn for impending hypo
        double bgThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , currentProfile.getUnits());
        boolean useFrame = SP.getBoolean(R.string.key_hypoppred_24hwindow, false);
        long frameStart = SP.getLong(R.string.key_hypoppred_window_from, 0L);
        long frameEnd = SP.getLong(R.string.key_hypoppred_window_to, 0L);
        int time = Profile.secondsFromMidnight() / 60;

        if ((lastBG <= bgThreshold)
                && (!useFrame || (time >= frameStart && time < frameEnd))) {
            timePCLastSatisfied = now();
            return true;
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

    private void endHypoTT(boolean wait15Min) {
        if (wait15Min) {
 // TODO: nu niet ivm testen andere zaken
            //  if (now() < timePCLastSatisfied + 15 * 60 * 1000)
 //               return;

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

        int hypoHorizon = SP.getInt(R.string.key_hypoppred_algorithm_horizon, 60);

        final Profile currentProfile = ProfileFunctions.getInstance().getProfile();
        if (currentProfile == null) {
            return false;
        }

        double bgThreshold = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_bg, 0d)
                , currentProfile.getUnits());

        if (Config.APS)
            predictionsAvailable = finalLastRun != null && finalLastRun.request.hasPredictions;
        else if (Config.NSCLIENT)
            predictionsAvailable = true;
        else
            predictionsAvailable = false;
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
                        && prediction.date < now() + hypoHorizon * 60 * 1000){

                    if(!prediction.isIOBPrediction)
                        return true;
                    else
                        iobLow = true;
                }
            }

            // IOB only reliable if no cargs on board, ie if COB not calculated
            if(iobLow && noCarbsOnBoard)
                return true;
        }

        return false;
    }

    /*
     * Hypo detection algorithm using polynomal fit.
     * */
    private PolynomialFunction bgCurve1 = null;
    private double[] bgFitCoeff1 = null;
    private PolynomialFunction bgCurve2 = null;
    private double[] bgFitCoeff2 = null;

    private void bgPolynomalFit() {
        boolean useFrame = SP.getBoolean(R.string.key_hypoppred_24hwindow, false);
        double weightFactor = SP.getDouble(R.string.key_hypoppred_algorithm_weight, 0.95d);
        int steps5Min = SP.getInt(R.string.key_hypoppred_algorithm_steps, 12);

        // Get recent observations
        long timeStartBG = 0;
        long timeMinBG = 0;
        double minBgValue = Double.MIN_VALUE;

        // All times are in sec since midnight
        long midnight = MidnightTime.calc();
        long nowTime = (now() - midnight) / 1000;
        long fromTime = nowTime - steps5Min * 5 * 60;

        IobCobCalculatorPlugin iobCobCalculatorPlugin = IobCobCalculatorPlugin.getPlugin();
        List<BgReading> bgReadingsArray = iobCobCalculatorPlugin.getBgReadings();

        bgCurve2 = null;
        bgFitCoeff2 = null;
        bgCurve1 = null;
        bgFitCoeff1 = null;
        if (bgReadingsArray == null || bgReadingsArray.size() == 0) {
            return;
        }

        BgReading bgr;
        WeightedObservedPoints obs = new WeightedObservedPoints();
        for (int i = 0; i < bgReadingsArray.size(); i++) {
            bgr = bgReadingsArray.get(i);
            long time = (bgr.date - midnight) / 1000;
            if (time <= fromTime) continue;
            int pow = (int) ((nowTime - time) / (60 * 5d));
            double weight = Math.pow(weightFactor, pow);
            obs.add(weight, time, bgr.value);
        }
        List lObs = obs.toList();
        if (lObs.size() < 3) return;

        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(1);
        bgFitCoeff1 = fitter.fit(lObs);
        bgCurve1 = new PolynomialFunction(bgFitCoeff1);
        fitter = PolynomialCurveFitter.create(2);
        bgFitCoeff2 = fitter.fit(lObs);
        bgCurve2 = new PolynomialFunction(bgFitCoeff2);
    }

    private boolean checkHypoUsingPolynomalFit() {
        if (bgCurve2 == null) {
            return false;
        }

        final Profile currentProfile = ProfileFunctions.getInstance().getProfile();
        if (currentProfile == null) {
            return false;
        }

        double hypoBG = SP.getDouble(R.string.low_mark,
                Profile.fromMgdlToUnits(OverviewPlugin.bgTargetLow, ProfileFunctions.getInstance().getProfileUnits()));
        double hypoAlertLevel = Profile.toMgdl(SP.getDouble(R.string.key_hypoppred_threshold_alert, 3.5d),
                currentProfile.getUnits());
        int hypoHorizon = SP.getInt(R.string.key_hypoppred_algorithm_horizon, 60);

        long midnight = MidnightTime.calc();
        long horizonSec = hypoHorizon * 60;
        long nowTime = now();
        long start = (nowTime - midnight) / 1000;
        long end = (nowTime - midnight) / 1000 + horizonSec;

        // Determine onset and depth of extremum
        double topt = 0, t1 = 0, t2 = 0d;
        double a = bgFitCoeff2[2];
        double b = bgFitCoeff2[1];
        double c = bgFitCoeff2[0] - hypoBG;
        if (a != 0) {
            double d;
            d = Math.pow(b, 2) - 4 * a * c;
            if (d >= 0) {
                t1 = (-b - Math.sqrt(d)) / (2 * a);
                t2 = (-b + Math.sqrt(d)) / (2 * a);
                if ((t1 >= start && t1 <= end) || (t2 >= start && t2 <= end))
                    return true;
                else
                    return false;
            } else {
                // not reaching thresholdBG
                boolean belowThreshold = (bgCurve2.value(start) < 0);
                return belowThreshold;
            }
        } else if (b != 0) {
            t1 = -c / b;
            boolean imminentHypo = (t1 >= start && t1 <= end);
            return imminentHypo;
        } else {
            // not reaching thresholdBG
            boolean belowThreshold = (bgCurve2.value(start) < 0);
            return belowThreshold;
        }
    }

    public List<BgReading> getFittedCurve2(long fromTime, long toTime) {
        if (bgCurve2 == null || !isEnabled(PluginType.GENERAL))
            return null;

        int steps5Min = SP.getInt(R.string.key_hypoppred_algorithm_steps, 12);
        long midnight = MidnightTime.calc();
        long start = Math.max(fromTime - midnight, now() - steps5Min * 5 * 60 * 1000 - midnight) / 1000;
        long end = (toTime - midnight) / 1000;

        List<BgReading> curve = new ArrayList<>();
        int nSteps = (int) (end - start) / 60;
        for (int i = 0; i <= nSteps; i++) {
            BgReading bg = new BgReading();
            bg.date = (start + i * 60) * 1000 + midnight;
            bg.value = bgCurve2.value(start + i * 60);
            curve.add(bg);
        }

        return curve;
    }

    public List<BgReading> getFittedCurve1(long fromTime, long toTime) {
        if (bgCurve1 == null || !isEnabled(PluginType.GENERAL))
            return null;

        int steps5Min = SP.getInt(R.string.key_hypoppred_algorithm_steps, 12);
        long midnight = MidnightTime.calc();
        long start = Math.max(fromTime - midnight, now() - steps5Min * 5 * 60 * 1000 - midnight) / 1000;
        long end = (toTime - midnight) / 1000;

        List<BgReading> curve = new ArrayList<>();
        int nSteps = (int) (end - start) / 60;
        for (int i = 0; i <= nSteps; i++) {
            BgReading bg = new BgReading();
            bg.date = (start + i * 60) * 1000 + midnight;
            bg.value = bgCurve1.value(start + i * 60);
            curve.add(bg);
        }

        return curve;
    }
}
