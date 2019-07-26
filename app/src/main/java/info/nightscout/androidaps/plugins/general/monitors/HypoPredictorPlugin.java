package info.nightscout.androidaps.plugins.general.monitors;

import com.squareup.otto.Subscribe;

import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.DataPointWithLabelInterface;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
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

        // Check after AndroidAPS restart if we had a TT running and sync with that
        synchronized (this) {
            TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
            if (currentTarget != null && currentTarget.reason.equals(MainApp.gs(R.string.hypo_detection))) {
                runningHypoTT = currentTarget;
                timePCLastSatisfied = now();
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
        try {
            if (this != getPlugin()) {
                if (L.isEnabled(L.AUTOSENS))
                    log.debug("Ignoring event for non default instance");
                return;
            }
            if (ev.bgReading != null)
                executeCheck(ev.bgReading.value);
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public synchronized void onEventTempTargetChange(final EventTempTargetChange ev) {
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

        // If not check if hypo is expected using polynomal fit
        if (true /*TODO: preference: use polynomal method?*/) {
            return checkHypoUsingPolynomalFit();
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
            if (now() < timePCLastSatisfied + 15 * 60 * 1000)
                return;

            if (previousTT != null && previousTT.isInProgress()) {
                // Previous TT would still be in progress so let it run for the remainder of its original duration
                int minutesRemaining = (int) (previousTT.end() - now()) / 60 / 1000;
                previousTT.date(DateUtil.roundDateToSec(now()))
                        .duration(minutesRemaining)
                        .reason(previousTT.reason + " (continued)");
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

    /*
     * Hypo detection algorithm using polynomal fit.
     * */
    private PolynomialFunction bgCurve = null;
    private boolean checkHypoUsingPolynomalFit() {

        int steps5Min = 12; //TODO: number of datapoints as preference parameter
        double weightFactor = 0.95d; //TODO: weight factor as preference parameter
        double hypoBG = 3.5d; //TODO: preference parameter
        long horizonmSec = 120*60*1000;

        // Get recent observations
        long timeStartBG = 0;
        long timeMinBG = 0;
        double minBgValue = Double.MIN_VALUE;
        long nowTime = now();
        long fromTime = nowTime - steps5Min * 5 * 60 * 1000;

        IobCobCalculatorPlugin iobCobCalculatorPlugin = IobCobCalculatorPlugin.getPlugin();
        List<BgReading> bgReadingsArray = iobCobCalculatorPlugin.getBgReadings();

        bgCurve = null;
        if (bgReadingsArray == null || bgReadingsArray.size() == 0) {
            return false;
        }

        BgReading bgr;
        WeightedObservedPoints obs = new WeightedObservedPoints();
        for (int i = 0; i < bgReadingsArray.size(); i++) {
            bgr = bgReadingsArray.get(i);
            if (bgr.date <= fromTime) continue;
            int pow = (int)((bgr.date-fromTime) / (60 * 1000 * 5d));
            double weight = Math.pow(weightFactor, pow);
            obs.add(weight, bgr.date, bgr.value);
        }
        List lObs = obs.toList();
        if (lObs.size() < 3) return false;

        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(2);
        double[] coeff = fitter.fit(lObs);

        // Determine onset and depth of extremum
        bgCurve = new PolynomialFunction(coeff);
        double topt = 0, t1 = 0, t2 = 0d;
        double a = coeff[2];
        double b = coeff[1];
        double c = coeff[0] - hypoBG;
        if (a != 0) {
            double d;
            d = Math.pow(b, 2) - 4 * a * c;
            if (d >= 0) {
                t1 = (-b - Math.sqrt(d)) / (2 * a) ;
                t2 = (-b + Math.sqrt(d)) / (2 * a) ;
                if ((t1 >= nowTime && t1 <= nowTime+horizonmSec) || (t2 >= nowTime && t2 <= nowTime+horizonmSec))
                    return true;
                else
                    return false;
            } else {
                // not reaching thresholdBG
                boolean belowThreshold = (bgCurve.value(nowTime) < 0);
                return belowThreshold;
            }
        } else if (b != 0) {
            t1 = -c / b;
            boolean imminentHypo = (t1 >= nowTime && t1 <= nowTime+horizonmSec);
            return imminentHypo;
        } else {
            // not reaching thresholdBG
            boolean belowThreshold = (bgCurve.value(nowTime) < 0);
            return belowThreshold;
        }
    }

    public List<BgReading>  getFittedCurve(long fromTime, long toTime) {
        if (bgCurve == null)
            return null;

        List<BgReading> curve = new ArrayList<>();
        int nSteps = (int) (toTime - fromTime) / ( 60 * 1000);
        for (int i = 0; i <= nSteps; i++) {
            BgReading bg = new BgReading();
            bg.value = bgCurve.value(fromTime + i * ( 60 * 1000));
            bg.date = fromTime + i *  60 * 1000;
            curve.add(bg);
        }

        return curve;
    }

    private class HypoDetails {
        private int minutesFromNow;
        private double lowestBG;
        private double currentBG;

        protected HypoDetails(int _mfn, double bg, double lBG) {
            minutesFromNow = _mfn;
            lowestBG = lBG;
            currentBG = bg;
        }

        protected double slope() {
            return (currentBG - lowestBG) / minutesFromNow;
        }
    }
}
