package info.nightscout.androidaps.plugins.hm.mealadvisor;

import android.content.Intent;

import com.squareup.otto.Subscribe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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
    private JSONArray meals = new JSONArray();

    private class ConsumedMeal {
        private JSONObject storage;

        ConsumedMeal() {
            String emptyData = "{\"notes\":\"\",\"carbs\":0,\"glycemicindex\":0,\"date\":0}";
            try {
                storage = new JSONObject(emptyData);
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
        }

        ConsumedMeal(JSONObject meal) {
            storage = meal;
        }

        ConsumedMeal(String meal) {
            try {
                storage = new JSONObject(meal);
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
        }

        public long getDate() {
            try {
                return storage.getLong("date");
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
            return 0;
        }

        public int getCarbs() {
            try {
                return storage.getInt("carbs");
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
            return 0;
        }

        public int getGlycemicIndex() {
            try {
                int gi=storage.getInt("glycemicindex");
                gi = gi==0?15:gi;
                return gi;
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
            return 0;
        }

        public String getNotes() {
            try {
                return storage.getString("notes");
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
            return "";
        }
    }

    public void setData() {
        String storedData = SP.getString("MealAdvisorMeals", "[]");
        try {
            meals = new JSONArray(storedData);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
    }

    public void save() {
        SP.putString("MealAdvisorMeals", meals.toString());
    }

    public ConsumedMeal getConsumedMeal(int position) {
        try {
            return new ConsumedMeal((JSONObject) meals.get(position));
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
        return null;
    }

    public double carbsLeft() {
        double carbsLeft = 0.0d;
        if (meals.length() > 0) {
            for (int i = meals.length() - 1; i >= 0; i--) {
                ConsumedMeal meal = getConsumedMeal(i);

                boolean mealHasCarbs = false;
                if (meal.getDate() < now() && meal.getDate() > now() - 45 * 60 * 1000) {
                    double timeFactor = (meal.getDate() + 45 * 60 * 1000 - now())*1.0d / (45 * 60 * 1000);
                    double mealSugarLeft = meal.getCarbs() * meal.getGlycemicIndex() * timeFactor / 100;
                    carbsLeft += mealSugarLeft;
                    mealHasCarbs = true;
                    log.info("Sugar left from '" + meal.getNotes() + "'@" + DateUtil.dateAndTimeFullString(meal.getDate()) + ":" +
                            mealSugarLeft + " (" +100*timeFactor + "% of " + meal.getCarbs()+"*"+meal.getGlycemicIndex()+"%)");
                }
                if (meal.getDate() < now() && meal.getDate() > now() - 120 * 60 * 1000) {
                    double timeFactor = (meal.getDate() + 120 * 60 * 1000 - now())*1.0d / (120 * 60 * 1000);
                    double mealCarbsLeft = meal.getCarbs() * (100-meal.getGlycemicIndex()) * timeFactor / 100;
                    carbsLeft += mealCarbsLeft;
                    mealHasCarbs = true;
                    log.info("Other carbs left from '" + meal.getNotes() + "'@" + DateUtil.dateAndTimeFullString(meal.getDate()) + ":" +
                            mealCarbsLeft + " (" + 100*timeFactor + "% of " + meal.getCarbs() +"*"+meal.getGlycemicIndex()+ "%)");
                }

                if(meal.getDate() < now() && !mealHasCarbs) {
                    log.info("Meal has no carbs left");
                    removeMeal(i);
                }
            }
        }
        return carbsLeft;
    }

    public double sugarLeft() {
        double sugarLeft = 0.0d;
        if (meals.length() > 0) {
            for (int i = meals.length() - 1; i >= 0; i--) {
                ConsumedMeal meal = getConsumedMeal(i);

                if (meal.getDate() < now() && meal.getDate() > now() - 45 * 60 * 1000) {
                    double timeFactor = (meal.getDate() + 45 * 60 * 1000 - now())*1.0d / (45 * 60 * 1000);
                    double mealSugarLeft = meal.getCarbs() * meal.getGlycemicIndex() * timeFactor / 100;
                    sugarLeft += mealSugarLeft;
                    log.info("Sugar left from '" + meal.getNotes() + "'@" + DateUtil.dateAndTimeFullString(meal.getDate()) + ":" +
                            mealSugarLeft + " (" +  100*timeFactor  + "% of " + meal.getCarbs()+"*"+meal.getGlycemicIndex() + "%)");
                }
            }
        }
        return sugarLeft;
    }

    public void addMeal(long mealDate, int mealCarbs, int mealGI, String mealNotes) {
        String data = "{\"notes\":\"" + mealNotes + "\",\"carbs\":" + mealCarbs + ",\"glycemicindex\":" + mealGI + ",\"date\":" + mealDate + "}";
        try {
            JSONObject meal = new JSONObject(data);
            meals.put(meal);
            save();
            log.info("Included meal @"+DateUtil.dateAndTimeFullString(meal.getLong("date"))+": " + data);
            if(mealCarbs == 0){
                log.info("Spurious meal!!!");
                new Exception().printStackTrace();
            }
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
    }

    public void removeMeal(int position) {
        try {
            JSONObject meal = meals.getJSONObject(position);
            log.info("Removed meal @"+DateUtil.dateAndTimeFullString(meal.getLong("date"))+": " + meal.toString());
            meals.remove(position);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
        save();
    }

    public void removeMeal(long date) {
        if (meals.length() > 0) {
            for (int i = meals.length() - 1; i >= 0; i--) {
                ConsumedMeal meal = getConsumedMeal(i);
                if (Math.abs(meal.getDate() - date) < 10 * 1000) {
                    log.info("Removed meal @" + DateUtil.dateAndTimeFullString(meal.getDate()) + ": " + meal.toString());
                    meals.remove(i);
                }
            }
        }
        save();
    }

    // Persistent state
    public void setMealBolusDate(long mealBolusDate) {
        log.info("Mealbolusdate set to " + DateUtil.dateAndTimeFullString(mealBolusDate));
        SP.putLong("mealBolusDate", mealBolusDate);
    }

    public void setMealDate(long mealDate) {
        SP.putLong("mealDate", mealDate);
    }

    public void setMealCarbs(double mealCarbs) {
        SP.putDouble("mealCarbs", mealCarbs);
    }

    public void setMealGI(double mealGI) {
        SP.putDouble("mealGI", mealGI);
    }

    public void setMealNotes(String mealNotes) {
        SP.putString("mealNotes", mealNotes);
    }

    public void setPreWarningRaised(boolean preWarningRaised) {
        log.info("Prewarningraised set to " + preWarningRaised);
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

    public double mealPercSugar() {
        return SP.getDouble("mealGI", 0.0);
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
        log.info("Set RescheduledMealTime to " + rescheduledMealTime);
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
TODO: pre-bolus en bolus bij start
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
            }

            if (now() - mealBolusDate() > 45 * 60 * 1000) {
                // Bolus > too long ago => cancel meal
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
                } else
                    log.info("PreWarning already raised.");
            }
        }
    }

    public void resetState() {

        setMealBolusDate(0L);

        // Cancel eating soon TT
        TempTarget currentTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory();
        if (currentTarget != null && currentTarget.reason.startsWith(MainApp.gs(R.string.eatingsoon))) {
            TempTarget tempTarget = new TempTarget()
                    .source(Source.USER)
                    .date(now())
                    .duration(0)
                    .low(0)
                    .high(0);
            TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTarget);
        }

        MainApp.bus().post(new EventRefreshOverview("mealadvisor"));
        log.info("State reset");
    }

    private void rescheduleMealTime() {
        double startBG = Profile.toMgdl(SP.getDouble(R.string.key_mealadvisor_startbg, 108d)
                , mCurrentProfile.getUnits());
        double deltaBG = Math.max((mLastStatus.glucose + mLastStatus.short_avgdelta * (10 / 5) - 4 * 18), 0);
        long carbTime = (long) (30 * deltaBG / (startBG - 4 * 18));
        carbTime = Math.max(carbTime, 20);
        long date = Math.max(mealBolusDate() + carbTime * 60 * 1000, now() + 2 * 60 * 1000);

        log.info("Rescheduled " + DateUtil.timeStringSeconds(mealDate()) + " to " + DateUtil.timeStringSeconds(date));
        setRescheduledMealTime(true);

        setMealDate(date);
        MainApp.bus().post(new EventRefreshOverview("mealadvisor"));
    }

    public synchronized boolean startMeal(int resourceID) {
        if (mLastStatus == null || mIobTotal == null || mCobInfo == null) return false;

        DetailedBolusInfo detailedBolusInfo = new DetailedBolusInfo();
        detailedBolusInfo.eventType = CareportalEvent.CARBCORRECTION;
        detailedBolusInfo.insulin = 0.0;
        detailedBolusInfo.carbs = mealCarbs();
        detailedBolusInfo.context = MainApp.instance().getApplicationContext();
        detailedBolusInfo.source = Source.USER;
        detailedBolusInfo.date = now();
        detailedBolusInfo.isSMB = false;
        detailedBolusInfo.notes = mealNotes();

        Treatment carbsTreatment = new Treatment();
        carbsTreatment.source = Source.USER;
        carbsTreatment.carbs = mealCarbs();
        carbsTreatment.date = now();
        TreatmentsPlugin.getPlugin().getService().createOrUpdate(carbsTreatment);
        NSUpload.uploadTreatmentRecord(detailedBolusInfo);
        addMeal(carbsTreatment.date, (int) mealCarbs(), (int) mealPercSugar(), mealNotes());

        Intent alarm = new Intent(MainApp.instance().getApplicationContext(), AlarmSoundService.class);
        alarm.putExtra("soundid", resourceID);
        MainApp.instance().startService(alarm);

        log.info("Meal started.");
        resetState();

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

        setData(); // meals in past 1,5 hr
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

    public void registerMeal(int carbs, int percSugar, String notes) {
        initState(true);

        log.info("Register meal '" + notes + "'");

        setMealBolusDate(now());
        setMealDate(mealBolusDate() + 90 * 60 * 1000); // 90 min so that if meal is not rescheduled it will be cancelled after 60 min
        setMealCarbs(carbs);
        setMealGI(percSugar);
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
