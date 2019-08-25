package info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor.algorithm;

import java.util.List;

import info.nightscout.androidaps.db.BgReading;

public interface BGCurveFitter {

    /*
    Implemented by base BG curvefitter
     */

    // Get points at 5 min intervals for timeframe (in msecs)
    public List<BgReading> getBgCurve(long fromTime, long toTime);

    // Fit to this set of BG readings
    public BGCurveFitter fit(List<BgReading> bgReadings);

    /*
    To be implemented by descendants. NOTE: times are always in mins, and relative to the time curve was fitted
     */

    // BG at time t
    public double value(double t);


    // Gradient wrt each parameter in mParms[] for time  t
    abstract public double[] gradient(double t);


    // Return smallest time where Bg is below threshold in interval [0,<horizon>], else -1.
    public double belowThresholdAt(double threshold, double horizon);

    // Return lowest BG within relative timeframe
    public double minimum(double fromTime, double toTime);

    // true if curve descends everywhere
    public boolean isDescending();
}
