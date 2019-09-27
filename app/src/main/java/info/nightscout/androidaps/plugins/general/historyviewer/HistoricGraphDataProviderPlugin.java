package info.nightscout.androidaps.plugins.general.historyviewer;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensResult;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.BasalData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;

import static info.nightscout.androidaps.utils.DateUtil.now;

public class HistoricGraphDataProviderPlugin extends PluginBase implements GraphDataProvider {
    private Logger log = LoggerFactory.getLogger(L.HGDPROV);

    // Cache for data in a single view. Initialised by call to getBGReadings(fromTime, toTime) so this should always be called first.
    private SortedMap<Long,HistoricGraphData> dataMap = dataMap = new TreeMap<>();
    private List keys5Min = new ArrayList();
    private List keys1Min = new ArrayList();
    private int lastTimeIndex = 0;

    private static HistoricGraphDataProviderPlugin plugin = new HistoricGraphDataProviderPlugin();

    public static HistoricGraphDataProviderPlugin getPlugin() {
        return plugin;
    }

    private HistoricGraphDataProviderPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.GENERAL)
                .neverVisible(true)
                .alwaysEnabled(true)
                .showInList(true)
                .pluginName(R.string.hgd_provider));
    }


    private void loadData(long fromTime, long toTime) {
        List<HistoricGraphData> data = MainApp.getDbHelper().getHistoricGraphData(fromTime, toTime);

        dataMap.clear();
        keys5Min.clear();
        keys1Min.clear();

        long last5MinRecord = 0;
        for (HistoricGraphData record:data) {
            dataMap.put(record.date,record);
            keys1Min.add(record.date);
            if(record.date - last5MinRecord > 5*60*1000-30){
                keys5Min.add(record.date);
                last5MinRecord = record.date;
            }
        }
    }

    @Override
    public List get5MinIntervals(long fromTime, long toTime) {
        return keys5Min;
    }

    @Override
    public List get1MinIntervals(long fromTime, long toTime) {
        return keys1Min;
    }

    public List<BgReading> getBGReadings(long fromTime, long toTime) {
        loadData(fromTime, toTime);

        List<BgReading> result = new ArrayList<BgReading>();
        for (int i = 0;i<keys1Min.size();i++) {
            HistoricGraphData record = dataMap.get(keys1Min.get(i));
            if (record.bg > 0) {
                BgReading bg = new BgReading();
                bg.date = record.date;
                bg.value = record.bg;

                result.add(bg);
            }
        }


        return result;
    }

    public IobTotal getActivity(long time, Profile profile) {
        IobTotal result = new IobTotal(time);

        result.activity = dataMap.get(time).activity;

        return result;
    }

    public double getIob(long time, Profile profile) {
        return dataMap.get(time).iob;
    }

    public AutosensData getCob(long time) {
        AutosensData result = new AutosensData();
        HistoricGraphData hit = dataMap.get(time);
        result.cob = hit.cob;
        result.carbsFromBolus = hit.carbsFromBolus;

        return result;
    }

    public AutosensData getDeviations(long time) {
        AutosensData result = new AutosensData();
        HistoricGraphData hit = dataMap.get(time);
        result.deviation = hit.deviation;
        result.pastSensitivity = hit.pastSensitivity;
        result.type = hit.type;

        return result;
    }

    public AutosensData getRatio(long time) {
        AutosensData result = new AutosensData();
        result.autosensResult = new AutosensResult();

        HistoricGraphData hit = dataMap.get(time);
        result.autosensResult.ratio = hit.sens;

        return result;
    }

    public AutosensData getSlope(long time) {
        AutosensData result = new AutosensData();
        HistoricGraphData hit = dataMap.get(time);

        result.slopeFromMaxDeviation = hit.slopeMax;
        result.slopeFromMinDeviation = hit.slopeMin;

        return result;
    }

    public BasalData getBasal(long time, Profile profile) {
        BasalData result = new BasalData();
        HistoricGraphData hit = dataMap.get(time);

        result.basal = hit.basal;
        result.isTempBasalRunning = hit.isTempBasalRunning;
        result.tempBasalAbsolute = hit.tempBasalAbsolute;

        return result;
    }

    public TempTarget getTempTarget(long time) {
        TempTarget result = new TempTarget();
        HistoricGraphData hit = dataMap.get(time);

        result.low = hit.target;

        result.high = result.low;

        return result;
    }

    // ------------- Data from other sources ----------------------------

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
    private ScheduledExecutorService mExecutor= null;

    @Override
    protected void onStart() {
        MainApp.bus().register(this);
        super.onStart();

        if (!isEnabled(PluginType.GENERAL)) return;

//todo remove or use        if (mExecutor == null)
            //mExecutor = Executors.newScheduledThreadPool(3);

        Runnable historicDataUpdater = new Runnable() {
                @Override
                public void run() {
                    updateHistoricGraphData(null);
                }
            };

        if(mExecutor != null)
            mExecutor.scheduleAtFixedRate(historicDataUpdater, 0, 60, TimeUnit.SECONDS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        MainApp.bus().unregister(this);


        if(mExecutor != null)
            mExecutor.shutdown();
        mExecutor = null;
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

        HistoricGraphData historicGraphData = new HistoricGraphData();

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


            historicGraphData.date = time;
            historicGraphData.bg = bg;
            historicGraphData.activity = (bolusIob != null) ? bolusIob.activity + basalIob.activity : 0;
            historicGraphData.basal = (basalData != null) ? basalData.basal : 0;
            historicGraphData.isTempBasalRunning = (basalData != null)? basalData.isTempBasalRunning : false;
            historicGraphData.tempBasalAbsolute = (basalData != null) ? basalData.tempBasalAbsolute : 0;
            historicGraphData.iob = (bolusIob != null) ? bolusIob.iob + basalIob.basaliob : 0;
            historicGraphData.cob = (autosensData != null) ? autosensData.cob : 0.0;
            historicGraphData.carbsFromBolus = (autosensData != null) ? autosensData.carbsFromBolus : 0.0;
            historicGraphData.failoverToMinAbsorbtionRate = (autosensData != null) ? autosensData.failoverToMinAbsorbtionRate : false;
            historicGraphData.deviation = (autosensData != null) ? autosensData.deviation : 0.0;
            historicGraphData.pastSensitivity = (autosensData != null) ? autosensData.pastSensitivity : "";
            historicGraphData.type = (autosensData != null) ? autosensData.type : "";
            historicGraphData.sens = (autosensData != null) ? autosensData.autosensResult.ratio : 0.0;
            historicGraphData.slopeMin = (autosensData != null) ? autosensData.slopeFromMinDeviation : 0.0;
            historicGraphData.slopeMax = (autosensData != null) ? autosensData.slopeFromMaxDeviation : 0.0;
            historicGraphData.target = (target != null) ? (target.low + target.high) / 2 :
                    Profile.toMgdl((profile.getTargetLow(now()) + profile.getTargetHigh(now())) / 2, profile.getUnits());

            log.info("Saving: " + historicGraphData);
            MainApp.getDbHelper().createOrUpdateHistoricGraphData(historicGraphData);
        } catch (Exception e) {
            log.info(e.getMessage());
            log.error("Unhandled exception", e);
        }
    }
}
