package info.nightscout.androidaps.plugins.general.historyviewer;

import java.util.ArrayList;
import java.util.List;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.BasalData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;

public class HistoricGraphDataHelper implements GraphDataProvider {
    
    IobCobCalculatorPlugin iobCobCalculatorPlugin = IobCobCalculatorPlugin.getPlugin();

    @Override
    public List<BgReading> getBGReadings(long fromTime, long toTime) {
        return iobCobCalculatorPlugin.getBgReadings();
    }

    @Override
    public IobTotal getActivity(long time, Profile profile) {
        return  iobCobCalculatorPlugin.calculateFromTreatmentsAndTempsSynchronized(time, profile);
    }

    @Override
    public double getIob(long time, Profile profile) {
        return iobCobCalculatorPlugin.calculateFromTreatmentsAndTempsSynchronized(time, profile).iob;
    }

    @Override
    public AutosensData getCob(long time) {
        return iobCobCalculatorPlugin.getAutosensData(time);
    }

    @Override
    public AutosensData getDeviations(long time) {
        return iobCobCalculatorPlugin.getAutosensData(time);
    }

    @Override
    public AutosensData getRatio(long time) {
        return iobCobCalculatorPlugin.getAutosensData(time);
    }

    @Override
    public AutosensData getSlope(long time) {
        return iobCobCalculatorPlugin.getAutosensData(time);
    }

    @Override
    public BasalData getBasal(long time, Profile profile) {
        return iobCobCalculatorPlugin.getBasalData(profile, time);
    }

    @Override
    public TempTarget getTempTarget(long time) {
        return TreatmentsPlugin.getPlugin().getTempTargetFromHistory(time);
    }

    // ------------- Data from other sources ----------------------------

    public List<Treatment> getTreatments(long fromTime, long endTime) {
        List<Treatment> result = new ArrayList<Treatment>();

        result = TreatmentsPlugin.getPlugin().getTreatmentsFromHistory();

        return result;
    }

    public List<ProfileSwitch> getProfileSwitches(long fromTime, long endTime) {
        List<ProfileSwitch> result = new ArrayList<ProfileSwitch>();

        result = MainApp.getDbHelper().getProfileSwitchEventsFromTime(fromTime, true);

        return result;
    }

    public List<ExtendedBolus> getExtendedBoluses(long fromTime, long endTime) {
        List<ExtendedBolus> result = new ArrayList<ExtendedBolus>();

        result =TreatmentsPlugin.getPlugin().getExtendedBolusesFromHistory().getList();

        return result;
    }

    public List<CareportalEvent> getcareportalEvents(long fromTime, long endTime) {
        List<CareportalEvent> result = new ArrayList<CareportalEvent>();

        result = MainApp.getDbHelper().getCareportalEventsFromTime(fromTime - 6 * 60 * 60 * 1000, true);

        return result;
    }
}
