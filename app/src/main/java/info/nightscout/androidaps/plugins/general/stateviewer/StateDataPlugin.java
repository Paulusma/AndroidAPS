package info.nightscout.androidaps.plugins.general.stateviewer;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.aps.loop.events.EventLoopUpdateGui;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.BasalData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;

import static info.nightscout.androidaps.utils.DateUtil.now;

public class StateDataPlugin extends PluginBase {
    private Logger log = LoggerFactory.getLogger(L.HGDPROV);

    private static StateDBHelper dbHelper = null;

    private static StateDataPlugin plugin = new StateDataPlugin();

    public static StateDataPlugin getPlugin() {
        return plugin;
    }

    private StateDataPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.GENERAL)
                .neverVisible(true)
                .alwaysEnabled(true)
                .showInList(true)
                .pluginName(R.string.hgd_provider));
    }

    // ------------- Get data ----------------------------

    public List<StateData> loadData(long fromTime, long toTime) {
        return MainApp.getDbHelper().getStateData(fromTime, toTime);
    }

    public List<Treatment> getTreatments(long fromTime, long endTime) {
        List<Treatment> result = new ArrayList<Treatment>();

        result = TreatmentsPlugin.getPlugin().getService().getTreatmentDataFromTime(fromTime, endTime, true);

        return result;
    }

    public List<ProfileSwitch> getProfileSwitches(long fromTime, long endTime) {
        List<ProfileSwitch> result = new ArrayList<ProfileSwitch>();

        result = MainApp.getDbHelper().getProfileSwitchEventsFromTime(fromTime, endTime, true);

        return result;
    }

    public List<ExtendedBolus> getExtendedBoluses(long fromTime, long endTime) {
        List<ExtendedBolus> result = new ArrayList<ExtendedBolus>();

        result = MainApp.getDbHelper().getExtendedBolusDataFromTime(fromTime, endTime, true);

        return result;
    }

    public List<CareportalEvent> getcareportalEvents(long fromTime, long endTime) {
        List<CareportalEvent> result = new ArrayList<CareportalEvent>();

        result = MainApp.getDbHelper().getCareportalEvents(fromTime - 6 * 60 * 60 * 1000, endTime, true);

        return result;
    }

    /*
    COLLECTION OF GRAPH DATA
     */
//    private ScheduledExecutorService mExecutor= null;

    @Override
    protected void onStart() {
        MainApp.bus().register(this);
        super.onStart();

        if (!isEnabled(PluginType.GENERAL)) return;

        dbHelper = new StateDBHelper(MainApp.instance());
/*
        if (mExecutor == null)
            mExecutor = Executors.newScheduledThreadPool(3);

        Runnable historicDataUpdater = new Runnable() {
                @Override
                public void run() {
                    updateHistoricGraphData(null);
                }
            };

        if(mExecutor != null)
            mExecutor.scheduleAtFixedRate(historicDataUpdater, 0, 60, TimeUnit.SECONDS);
  */
    }

    @Override
    protected void onStop() {
        super.onStop();
        MainApp.bus().unregister(this);

        if (dbHelper != null) {
            dbHelper.close();
            dbHelper = null;
        }
/*
        if(mExecutor != null)
            mExecutor.shutdown();
        mExecutor = null;
*/
    }


    @Subscribe
    @SuppressWarnings("unused")
    public void onEventLoopUpdateGui(final EventLoopUpdateGui ev) {
        try {
            if (!isEnabled(PluginType.GENERAL)) return;
            new Thread(() -> updateHistoricGraphData(DatabaseHelper.lastBg())).start();
        } catch (Exception e) {
            log.info(e.getMessage());
            log.error("Unhandled exception", e);
        }
    }


    static long lastEventLoopUpdateGui = 0L;
    private synchronized void updateHistoricGraphData(BgReading bgr) {
        long time = now();
        double bg;
        if(bgr != null) {
            if (time - lastEventLoopUpdateGui < 10 * 1000){
                // skip multiple events
                return;
            }
            lastEventLoopUpdateGui = time;
            bg = bgr.value;
            log.info("EventLoopUpdateGui fired");
        }else{
            bg = 0.0d;
            log.info("Timer fired");
        }

        StateData state = new StateData();

        try {
            Profile profile = ProfileFunctions.getInstance().getProfile(time);
            if (profile == null) {
                log.info("Exit: profile NULL!");
                return;
            }
            // TODO non-BG data lag 5 mins behind?! (including BG target!)
            // TODO some points seem shifted?
            // TODO missed BG-readings are not properly restored after they are delayed-received

            // From graphdata...
            final IobTotal bolusIob = TreatmentsPlugin.getPlugin().getCalculationToTimeTreatments(time);
            final IobTotal basalIob = TreatmentsPlugin.getPlugin().getCalculationToTimeTempBasals(time, profile);
            AutosensData autosensData = IobCobCalculatorPlugin.getPlugin().getAutosensData(time);
            BasalData basalData = IobCobCalculatorPlugin.getPlugin().getBasalData(profile, time);
            TempTarget target = TreatmentsPlugin.getPlugin().getTempTargetFromHistory(time);

            state.date = time;
            state.bg = bg;
            state.activity = (bolusIob != null) ? bolusIob.activity + basalIob.activity : 0;
            state.basal = (basalData != null) ? basalData.basal : 0;
            state.isTempBasalRunning = (basalData != null)? basalData.isTempBasalRunning : false;
            state.tempBasalAbsolute = (basalData != null) ? basalData.tempBasalAbsolute : 0;
            state.iob = (bolusIob != null) ? bolusIob.iob + basalIob.basaliob : 0;
            state.cob = (autosensData != null) ? autosensData.cob : 0.0;
            state.carbsFromBolus = (autosensData != null) ? autosensData.carbsFromBolus : 0.0;
            state.failoverToMinAbsorbtionRate = (autosensData != null) ? autosensData.failoverToMinAbsorbtionRate : false;
            state.deviation = (autosensData != null) ? autosensData.deviation : 0.0;
            state.pastSensitivity = (autosensData != null) ? autosensData.pastSensitivity : "";
            state.type = (autosensData != null) ? autosensData.type : "";
            state.sens = (autosensData != null) ? autosensData.autosensResult.ratio : 0.0;
            state.slopeMin = (autosensData != null) ? autosensData.slopeFromMinDeviation : 0.0;
            state.slopeMax = (autosensData != null) ? autosensData.slopeFromMaxDeviation : 0.0;
            state.target = (target != null) ? (target.low + target.high) / 2 :
                    Profile.toMgdl((profile.getTargetLow(now()) + profile.getTargetHigh(now())) / 2, profile.getUnits());

            log.info("Saving: " + state);
            dbHelper.createOrUpdateStateData(state);
        } catch (Exception e) {
            log.info(e.getMessage());
            log.error("Unhandled exception", e);
        }
    }

}
