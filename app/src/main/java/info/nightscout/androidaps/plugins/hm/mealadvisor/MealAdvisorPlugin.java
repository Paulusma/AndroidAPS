package info.nightscout.androidaps.plugins.hm.mealadvisor;

import android.content.Intent;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.events.EventRefreshOverview;
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
import info.nightscout.androidaps.plugins.treatments.CarbsGenerator;
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

    // Persistent state
    public void setMealBolusDate(long mealBolusDate) {
        SP.putLong("mealBolusDate", mealBolusDate);
    }

    public void setMealDate(long mealDate) {
        SP.putLong("mealDate", mealDate);
    }

    public void setMealCarbs(double mealCarbs) {
        SP.putDouble("mealCarbs", mealCarbs);
    }

    public void setMealNotes(String mealNotes) {
        SP.putString("mealNotes", mealNotes);
    }

    public void setPreWarningRaised(boolean preWarningRaised) {
        SP.putBoolean("preWarningRaised", preWarningRaised);
    }

    public long mealBolusDate() {
        return SP.getLong("mealBolusDate", 0L);
    }

    public long mealDate() {
        return SP.getLong("mealDate", 0L);
    }

    public double mealCarbs() {
        return SP.getDouble("mealCarbs", 0.0);
    }

    public String mealNotes() {
        return SP.getString("mealNotes", "");
    }

    public boolean preWarningRaised() {
        return SP.getBoolean("preWarningRaised", Boolean.FALSE);
    }

    public boolean rescheduledMealTime() {
        return SP.getBoolean("rescheduledMealTime", Boolean.FALSE);
    }

    public void setRescheduledMealTime(boolean rescheduledMealTime) {
        SP.putBoolean("rescheduledMealTime", rescheduledMealTime);
    }

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

    public double getScheduledCarbs() {
        return (mealBolusDate() > 0 ? mealCarbs() : 0.0);
    }

    ;

    public String getMealNotes() {
        return (mealBolusDate() > 0 ? mealNotes() : "");
    }

    ;

    public String getMealTime() {
        return (mealBolusDate() > 0 ? DateUtil.timeString(mealDate()) : "");
    }

    ;

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
    - v vervang tijd door radio's NU en LATER. NU werkt zoals eerder, LATER via mealadvisor
    - v maak treatment pas aan als maaltijd start
    - v zet actuele estimate waarop kan worden begonnen met eten onder Boluswizard icoon
    - v longpress op boluswizard icoon opent dialoog met afbreken/starten maaltijd

    Controle:
    - v na 1 uur volgt melding dat opnieuw moet worden geblust cq maaltijd opnieuw wordt ingevoerd
    - v wachttijd afhankelijk van BG, trend en moment van bolus: loopt 30 min naar 0
    - v check <4 wordt <4.5 en dalend
    - v waarschuwen als er al een maaltijd is ingepland

    Robuustheid:
    -   sla state realtime op in preferences: bolustijd, #carbs, notitie!!!!!!!!!!!!!
  */

    private synchronized void executeCheck() {
        if (mLastStatus == null || mIobTotal == null || mCobInfo == null) return;

        boolean pcSatisfied = preconditionSatisfied();

        if (pcSatisfied) {
            long now = System.currentTimeMillis();
            if (mealDate() <= now) {
                // Times up => start meal
                /* todo: check if additional insulin is needed: IOB not sufficient */
                if (false /* todo no extra insulin needed */) {
                    log.info("Time " + DateUtil.timeStringSeconds(mealDate()) + " => start meal (need extra bolus)");
                    startMeal(R.raw.time_startmeal);
                } else {
                    log.info("Time " + DateUtil.timeStringSeconds(mealDate()) + " => start meal.");
                    startMeal(R.raw.time_startmeal);
                }
                return;
            } else if (mLastStatus.glucose + mLastStatus.delta * (10 / 5) <= 4 * 18) {
                // BG getting too low => start meal
                log.info("Meal " + DateUtil.timeStringSeconds(mealDate()) + " pending but low BG => start meal.");
                startMeal(R.raw.low_startmeal);
                return;
            }

            if (now() - mealBolusDate() > 60 * 60 * 1000) {
                // Bolus > 1hr ago => cancel meal
                Intent alarm = new Intent(MainApp.instance().getApplicationContext(), AlarmSoundService.class);
                alarm.putExtra("soundid", R.raw.bolusgt1hr);
                log.info("Meal " + DateUtil.timeStringSeconds(mealDate()) + " pending but bolus > 1hr ago => cancel meal.");
                resetState();
                MainApp.instance().startService(alarm);
                return;
            }

            double startBG = Profile.toMgdl(SP.getDouble(R.string.key_mealadvisor_startbg, 108d)
                    , mCurrentProfile.getUnits());
            if (mLastStatus.glucose + mLastStatus.delta * (10 / 5) <= startBG) {
                // expected BG at now + 10 mins low enough for meal to be scheduled
                if (!preWarningRaised() && !rescheduledMealTime()) rescheduleMealTime();
            } else {
                // BG/trend NOT low enough for meal to be scheduled: cancel previous schedule
                setRescheduledMealTime(false);
            }

            long minsWarn = SP.getLong(R.string.key_mealadvisor_minsprewarn, 5l);
            if (mealDate() <= now + minsWarn * 60 * 1000) {
                if (!preWarningRaised()) {
                    Intent alarm = new Intent(MainApp.instance().getApplicationContext(), AlarmSoundService.class);
                    alarm.putExtra("soundid", R.raw.prewarn_startmeal);
                    MainApp.instance().startService(alarm);
                    setPreWarningRaised(true);
                    log.info("Warning " + DateUtil.timeStringSeconds(mealDate()) + ": eating starts soon.");
                }
            }
        }
    }

    public void resetState() {

        setMealBolusDate(0L);

        // Cancel eating soon TT
        TempTarget tempTarget = new TempTarget()
                .source(Source.USER)
                .date(now())
                .duration(0)
                .low(0)
                .high(0);
        TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTarget);
    }

    private void rescheduleMealTime() {
        double startBG = Profile.toMgdl(SP.getDouble(R.string.key_mealadvisor_startbg, 108d)
                , mCurrentProfile.getUnits());
        double deltaBG = Math.max((mLastStatus.glucose + mLastStatus.short_avgdelta * (10 / 5) - 4 * 18), 0);
        long carbTime = (deltaBG <= 0 ? 0 : (long) (30 * deltaBG / (startBG - 4 * 18)));
        long date = Math.max(mealBolusDate() + carbTime * 60 * 1000, now() + 2 * 60 * 1000);

        log.info("Rescheduled " + DateUtil.timeStringSeconds(mealDate()) + " to " + DateUtil.timeStringSeconds(date));
        setRescheduledMealTime(true);

        setMealDate(date);
        MainApp.bus().post(new EventRefreshOverview("mealadvisor"));
    }

    public synchronized boolean startMeal(int resourceID) {
        if (mLastStatus == null || mIobTotal == null || mCobInfo == null) return false;

        DetailedBolusInfo detailedBolusInfo = new DetailedBolusInfo();
        detailedBolusInfo.date = now();
        detailedBolusInfo.carbTime = 0;
        detailedBolusInfo.eventType = CareportalEvent.BOLUSWIZARD;
        detailedBolusInfo.insulin = 0.0;
        detailedBolusInfo.carbs = mealCarbs();
        detailedBolusInfo.context = null;
        detailedBolusInfo.glucose = mLastStatus.glucose;
        detailedBolusInfo.glucoseType = "Manual";
        detailedBolusInfo.source = Source.USER;
        detailedBolusInfo.isValid = true;

        CarbsGenerator.generateCarbs((int)mealCarbs(), now(), 0, mealNotes());
        NSUpload.uploadEvent(CareportalEvent.NOTE, now(), MainApp.gs(R.string.generated_ecarbs_note, mealCarbs(), 0, 0));

        Intent alarm = new Intent(MainApp.instance().getApplicationContext(), AlarmSoundService.class);
        alarm.putExtra("soundid", resourceID);
        MainApp.instance().startService(alarm);

        setMealBolusDate(0L);

        resetState();
        MainApp.bus().post(new EventRefreshOverview("mealadvisor"));

        return true;
    }

    private boolean preconditionSatisfied() {

        if (mealBolusDate() > 0) {
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

    public void registerMeal(int carbs, String notes) {
        initState(true);

        log.info("Register meal '" + notes + "'");

        setMealBolusDate(now());
        setMealDate(mealBolusDate() + 90 * 60 * 1000); // 90 min so that if meal is not rescheduled it will be cancelled after 60 min
        setMealCarbs(carbs);
        setMealNotes(notes);

        setPreWarningRaised(false);
        setRescheduledMealTime(false);

        // Start 'eating soon' target (note: after 60 min meal will be cancelled)
        TempTarget tempTarget = new TempTarget()
                .date(System.currentTimeMillis())
                .duration(60)
                .reason(MainApp.gs(R.string.eatingsoon))
                .source(Source.USER)
                .low(Profile.toMgdl(4.0, mCurrentProfile.getUnits()))
                .high(Profile.toMgdl(4.0, mCurrentProfile.getUnits()));
        TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTarget);
        MainApp.bus().post(new EventRefreshOverview("mealadvisor"));
    }
}
