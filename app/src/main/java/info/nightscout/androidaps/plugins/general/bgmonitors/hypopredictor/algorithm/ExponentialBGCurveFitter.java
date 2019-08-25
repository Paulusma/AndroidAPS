package info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor.algorithm;

import org.apache.commons.math3.fitting.WeightedObservedPoint;

import java.util.List;

public class ExponentialBGCurveFitter extends BaseBGCurveFitter {

    public ExponentialBGCurveFitter() {
        super(3);
    }

    public double value(double t) {
        return mParms[0] * Math.exp(-mParms[1] * t) + mParms[2];
    }

    public double[] gradient(double t) {
        // Array of partial derivatives in mParms[i]
        return new double[]{
                Math.exp(-t * mParms[1]),
                -mParms[0] * t * (Math.exp(-t * mParms[1])),
                1
        };
    }

    protected double[] getInitialGuess(List<WeightedObservedPoint> lObs) {
        double[] initialGuess = new double[3];
        WeightedObservedPoint obsMax = lObs.get(0);
        WeightedObservedPoint obsMin = lObs.get(lObs.size() - 1);
        double grad = (obsMax.getY() - obsMin.getY()) / (obsMax.getX() - obsMin.getX());
        initialGuess[0] = 18;
        initialGuess[1] = -grad / 18;
        initialGuess[2] = obsMax.getY() - 18;

        return initialGuess;
    }

    // Return smallest time where Bg is below threshold in interval [0,<horizon>], else BgLow.NONE
    // BG=parm[0]exp(-parm[1]*t)+parm[2]
    public double belowThresholdAt(double threshold, double horizon) {
        if (mParms[1] < 0){
            // Treat as error: should never be the case as exponential fit is only made on descending Bg's
            return -1;
        }

        if (mParms[2] == threshold)
            return -1;

        if (mParms[2] > threshold && mParms[0] >= 0)
            return -1;

        if(mParms[2] < threshold && mParms[0] <= 0)
            return 0;

        long t = (long) (-(1 / mParms[1]) * Math.log((threshold - mParms[2]) / mParms[0]));
        if (mParms[2] > threshold && mParms[0] < 0) {
            if (t <0)
                return -1;
            else
                return 0;
        }else if (mParms[2] < threshold && mParms[0] > 0) {
            if (t > horizon)
                return -1;
            else
                return Math.max(t,0);
        }

        return -1;
    }

    public double minimum(double fromTime, double toTime) {
        return value(toTime);
    }

    public boolean isDescending() {
        return (mParms[1]>0);
    }
}
