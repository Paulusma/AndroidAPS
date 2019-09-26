package info.nightscout.androidaps.plugins.general.historyviewer;

import java.util.List;

import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.BasalData;
import info.nightscout.androidaps.plugins.treatments.Treatment;

public interface GraphDataProvider {
    public List<BgReading> getBGReadings(long fromTime, long toTime);

    public IobTotal getActivity(long time, Profile profile);

    public double getIob(long time, Profile profile);

    public AutosensData getCob(long time);

    public AutosensData getDeviations(long time);

    public AutosensData getRatio(long time);

    public AutosensData getSlope(long time);

    public BasalData getBasal(long time, Profile profile);

    public TempTarget getTempTarget(long time);

    public List<Treatment> getTreatments(long fromTime, long endTime);

    public List<ProfileSwitch> getProfileSwitches(long fromTime, long endTime);

    public List<ExtendedBolus> getExtendedBoluses(long fromTime, long endTime);

    public List<CareportalEvent> getcareportalEvents(long fromTime, long endTime);
}
