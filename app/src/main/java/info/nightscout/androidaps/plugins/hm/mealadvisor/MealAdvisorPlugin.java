package info.nightscout.androidaps.plugins.hm.mealadvisor;

import android.content.Intent;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.CobInfo;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.events.EventAutosensCalculationFinished;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.services.AlarmSoundService;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;

import static info.nightscout.androidaps.utils.DateUtil.now;

public class MealAdvisorPlugin extends PluginBase {

    private static Logger log = LoggerFactory.getLogger(L.MEALADVISOR);
    private static MealAdvisorPlugin thePlugin;

    // State information
    private Profile mCurrentProfile = null;
    private GlucoseStatus mLastStatus = null;
    private CobInfo mCobInfo = null;
    private IobTotal mIobTotal = null;

    private Treatment mNextMeal = null;
    private boolean preWarningRaised = false;
    private boolean startMealRaised = false;
    private boolean mealRescheduled = false;

    private MealAdvisorPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.GENERAL)
                .pluginName(R.string.mealadvisor)
                .shortName(R.string.mealadvisor_shortname)
                .neverVisible(true)
                .alwaysEnabled(true)
                .preferencesId(R.xml.pref_mealadvisor)
                .description(R.string.description_mealadvisor)
        );
    }

    public static MealAdvisorPlugin getPlugin() {
        if (thePlugin == null) {
            thePlugin = new MealAdvisorPlugin();
        }

        return thePlugin;
    }

    @Override
    protected void onStart() {
        log.info("");
        MainApp.bus().register(this);
        super.onStart();

        if (!isEnabled(PluginType.GENERAL)) return;

        try {
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
    public synchronized void onEventPreferenceChange(final EventPreferenceChange ev) {
        try {
            if (!isEnabled(PluginType.GENERAL) || !initState(false)) return;

            if (ev.isChanged(R.string.key_mealadvisor_startbg) ||
                    ev.isChanged(R.string.key_mealadvisor_minsprewarn)) {
                executeCheck();
            }
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }
/*
    Boluswizard:
    - vervang tijd door radio's NU en LATER. NU werkt zoals eerder, LATER via mealadvisor
    - maak treatment pas aan als maaltijd start
    - zet actuele estimate waarop kan worden begonnen met eten onder Boluswizard icoon
    - longpress op boluswizard icoon opent dialoog met afbreken/starten maaltijd

    Controle:
    - na 1 uur volgt melding dat opnieuw moet worden geblust cq maaltijd opnieuw wordt ingevoerd
    - wachttijd afhankelijk van BG, trend en moment van bolus: loopt 30 min naar 0
    - check <4 wordt <4.5 en dalend

    Robuustheid:
    - sla state realtime op in preferences: bolustijd, #carbs, notitie
  */

private synchronized void executeCheck() {
        if (mLastStatus == null || mIobTotal == null || mCobInfo == null) return;

        boolean pcSatisfied = preconditionSatisfied();
        /* mNextMeal now contains next meal treatment. */

        if (pcSatisfied) {
            long now = System.currentTimeMillis();
            if (mNextMeal.date <= now) {
                if (!startMealRaised) {
                    Intent alarm = new Intent(MainApp.instance().getApplicationContext(), AlarmSoundService.class);
                    /* todo: check if additional insulin is needed: IOB not sufficient */
                    if (false /* todo no extra insulin needed */) {
                        alarm.putExtra("soundid", R.raw.time_startmeal /* todo additional bolus */);
                        log.info("Time " + DateUtil.timeStringSeconds(mNextMeal.date) + " => start eating (need extra bolus)");
                    } else {
                        alarm.putExtra("soundid", R.raw.time_startmeal);
                        log.info("Time " + DateUtil.timeStringSeconds(mNextMeal.date) + " => start eating.");
                    }
                    if (!mealRescheduled) {
                        updateMealTime(now);
                        mealRescheduled = true;
                    }
                    MainApp.instance().startService(alarm);
                    startMealRaised = true;
                    preWarningRaised = true;
                }
                return;
            } else if (mLastStatus.glucose <= 4 * 18) {
                if (!startMealRaised) {
                    Intent alarm = new Intent(MainApp.instance().getApplicationContext(), AlarmSoundService.class);
                    alarm.putExtra("soundid", R.raw.low_startmeal);
                    updateMealTime(now);
                    log.info("Meal " + DateUtil.timeStringSeconds(mNextMeal.date) + " pending but low BG => start eating.");
                    startMealRaised = true;
                    MainApp.instance().startService(alarm);
                }
                return;
            }

            double startBG = Profile.toMgdl(SP.getDouble(R.string.key_mealadvisor_startbg, 108d)
                    , mCurrentProfile.getUnits());
            if (mLastStatus.glucose <= startBG) {//todo&& !bgIsNotDropping()){
                if (!mealRescheduled) {
                    updateMealTime(Math.max(mNextMeal.date - 30 * 60 * 1000, now + 2 * 60 * 1000));
                    mealRescheduled = true;
                }
            }

            long minsWarn = SP.getLong(R.string.key_mealadvisor_minsprewarn, 5l);
            if (mNextMeal.date <= now + minsWarn * 60 * 1000) {
                if (!preWarningRaised) {
                    Intent alarm = new Intent(MainApp.instance().getApplicationContext(), AlarmSoundService.class);
                    alarm.putExtra("soundid", R.raw.prewarn_startmeal);
                    MainApp.instance().startService(alarm);
                    preWarningRaised = true;
                    log.info("Warning " + DateUtil.timeStringSeconds(mNextMeal.date) + ": eating starts soon.");
                }
            }
        }
    }

    private void updateMealTime(long date) {
        long oldDate = mNextMeal.date;
        long now = DatabaseHelper.roundDateToSec(now());
        int carbTime = Math.max(0, (int) (date - now) / (60 * 1000));

        TreatmentsPlugin.getPlugin().getService().delete(mNextMeal);
        mNextMeal.date = now + carbTime * 60 * 1000L;
        TreatmentsPlugin.getPlugin().getService().createOrUpdate(mNextMeal);
        syncWithNS(now,carbTime);

        log.info("Rescheduled " + DateUtil.timeStringSeconds(oldDate) + " to " + DateUtil.timeStringSeconds(mNextMeal.date));

        mealRescheduled = true;
    }

    private void syncWithNS(long date,int carbTime){
        DetailedBolusInfo detailedBolusInfo = new DetailedBolusInfo();
        detailedBolusInfo.date = date;
        detailedBolusInfo.carbTime = carbTime;
        detailedBolusInfo.eventType = CareportalEvent.BOLUSWIZARD;
        detailedBolusInfo.insulin = 0.0;
        detailedBolusInfo.carbs = mNextMeal.carbs;
        detailedBolusInfo.context = null;
        detailedBolusInfo.glucose = mLastStatus.glucose;
        detailedBolusInfo.glucoseType = "Manual";
        detailedBolusInfo.source = Source.USER;
        NSUpload.uploadTreatmentRecord(detailedBolusInfo);
    }

    private boolean preconditionSatisfied() {

        // Loop only runs every 5 mins so include meals that are max 6 mins old as well
        List<Treatment> futureTreatments = TreatmentsPlugin.getPlugin().getService()
                .getCarbDataFromTime(now() - 6 * 60 * 1000, true);
        if (futureTreatments.size() > 0) {
            Treatment meal = futureTreatments.get(0);
            if (mNextMeal != null) {
                if (meal.date != mNextMeal.date && meal.date + 1000l != mNextMeal.date) {
                    // new meal... todo: igv sstart meal is meal.date 1 sec achter mNextmeal.date!???
                    log.info("Next meal " + DateUtil.timeStringSeconds(mNextMeal.date) + " -> " + DateUtil.timeStringSeconds(meal.date));
                    preWarningRaised = false;
                    startMealRaised = false;
                    mealRescheduled = false;
                }
            }
            mNextMeal = meal;
            log.info("Next meal " + DateUtil.timeStringSeconds(mNextMeal.date));
        } else {
            log.info("No next meal found");
            preWarningRaised = false;
            startMealRaised = false;
            mealRescheduled = false;
            mNextMeal = null;
        }

        if (mNextMeal != null) {
            log.info("Precondition satisfied");
            return true;
        }
        log.info("Precondition NOT satisfied");
        return false;
    }

    private boolean initState(boolean forceLastStatus) {
        mCurrentProfile = ProfileFunctions.getInstance().getProfile();

        if (forceLastStatus || mLastStatus == null) {
            mLastStatus = GlucoseStatus.getGlucoseStatusData(true);
            mCobInfo = IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "Hypo detection");
        }

        if (mCurrentProfile != null) {
            mIobTotal = IobCobCalculatorPlugin.getPlugin().calculateFromTreatmentsAndTempsSynchronized(now(), mCurrentProfile);
        }

        return (mCurrentProfile != null);
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

}
