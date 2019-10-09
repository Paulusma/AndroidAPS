package info.nightscout.androidaps.plugins.hm.hypopredictor.algorithm;

import org.apache.commons.math3.fitting.WeightedObservedPoint;

import java.util.List;

public class LinearBGCurveFitter extends BaseBGCurveFitter {

    public LinearBGCurveFitter() {
        super(2);
    }

    public double value(double t) {
        return mParms[0] + mParms[1] * t;
    }

    public double[] gradient(double t) {
        return new double[]{1, t};
    }

    protected double[] getInitialGuess(List<WeightedObservedPoint> lObs) {
        double[] initialGuess = new double[2];
        WeightedObservedPoint obsMax = lObs.get(0);
        WeightedObservedPoint obsMin = lObs.get(lObs.size() - 1);
        initialGuess[0] = obsMin.getY();
        initialGuess[1] = (obsMax.getY() - obsMin.getY()) / (obsMax.getX() - obsMin.getX());

        return initialGuess;
    }

    // Return smallest time where Bg is below threshold in interval [0,<horizon>], else BgLow.NONE
    // BG=parm[0]+parm[1]*t
    public double belowThresholdAt(double threshold, double horizon) {

        if(mParms[1] == 0)
            return mParms[0]> threshold ? -1 :0;

        long t = (long) ((threshold - mParms[0]) / mParms[1]);
        if (mParms[0] > threshold) {
            if (t > horizon || t < 0)
                return -1;
            else
                return t;
        }else
            return 0;
    }

    public double minimum(double fromTime, double toTime) {
        if (mParms[1] < 0)
            return value(toTime);

        return value(fromTime);
    }

    public boolean isDescending() {
        return (mParms[1] < 0);
    }
}
