package info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor.algorithm;

import org.apache.commons.math3.analysis.ParametricUnivariateFunction;
import org.apache.commons.math3.exception.InsufficientDataException;
import org.apache.commons.math3.exception.util.LocalizedFormats;
import org.apache.commons.math3.fitting.AbstractCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoint;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.linear.DiagonalMatrix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import info.nightscout.androidaps.R;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.utils.SP;

import static info.nightscout.androidaps.utils.DateUtil.now;

/*
Interface to apache curvefitting routines.
 */
abstract public class BaseBGCurveFitter extends AbstractCurveFitter implements ParametricUnivariateFunction, BGCurveFitter {

    protected double[] mParms = null;
    private int mNofParms = 0;

    private BaseBGCurveFitter() {
    }

    public double[] getParms(){return mParms;}

    protected BaseBGCurveFitter(int size) {
        mNofParms = size;
    }

    public double value(double t, double... parameters) {
        mParms = parameters;
        return value(t);
    }

    public double[] gradient(double t, double... parameters) {
        mParms = parameters;
        return gradient(t);
    }

    protected LeastSquaresProblem getProblem(Collection<WeightedObservedPoint> points) {
        final int len = points.size();
        final double[] target = new double[len];
        final double[] weights = new double[len];

        int i = 0;
        for (WeightedObservedPoint point : points) {
            target[i] = point.getY();
            weights[i] = point.getWeight();
            i += 1;
        }

        final AbstractCurveFitter.TheoreticalValuesFunction model = new
                AbstractCurveFitter.TheoreticalValuesFunction(this, points);

        return new LeastSquaresBuilder().
                maxEvaluations(10000).
                maxIterations(10000).
                start(mParms).
                target(target).
                weight(new DiagonalMatrix(weights)).
                model(model.getModelFunction(), model.getModelFunctionJacobian()).
                build();
    }

    public BaseBGCurveFitter fit(List<BgReading> bgReadings) {
        init(bgReadings);
        if (mObs.size() < mNofParms)
            throw new InsufficientDataException(LocalizedFormats.INSUFFICIENT_OBSERVED_POINTS_IN_SAMPLE,
                    mObs.size(), mNofParms);

        mParms = getInitialGuess(mObs);
        mParms = super.fit(mObs);
        value(0, mParms);

        return this;
    }

    /*
     * Curve fitting
     * */
    private List<WeightedObservedPoint> mObs = null;
    private long mFittedCurveOffset = 0;

    private void init(List<BgReading> bgReadings) {
        double weightFactor = SP.getDouble(R.string.key_hypoppred_algorithm_weight, 0.95d);
        int steps5Min = SP.getInt(R.string.key_hypoppred_algorithm_steps, 12);

        if (bgReadings == null || bgReadings.size() == 0) {
            return;
        }

        mLastFromTime = 0;
        mLastToTime = 0;

        // All times are in secs relative to calculation (=current) time
        mFittedCurveOffset = now() / 1000 / 60;
        long start = -steps5Min * 5;

        WeightedObservedPoints obs = new WeightedObservedPoints();
        BgReading bgr;
        for (int i = 0; i < bgReadings.size(); i++) {
            bgr = bgReadings.get(i);
            long time = bgr.date / 1000 / 60 - mFittedCurveOffset;
            if (time <= start) continue;
            int pow = (int) (-time / 5);
            double weight = Math.pow(weightFactor, pow);
            obs.add(weight, time, bgr.value);
        }
        mObs = obs.toList();
    }

    // Keep data of last fit in cache to speedup UI re-draws
    private List<BgReading> mBgCurve = new ArrayList<>();
    private long mLastFromTime = 0, mLastToTime = 0;

    public List<BgReading> getBgCurve(long fromTime, long toTime) {
        if (!mBgCurve.isEmpty() && (mLastFromTime == fromTime && mLastToTime == toTime)) {
            return mBgCurve;
        }

        mBgCurve.clear();
        mLastFromTime = fromTime;
        mLastToTime = toTime;

        int steps5Min = SP.getInt(R.string.key_hypoppred_algorithm_steps, 12);
        long start = Math.max(fromTime / 1000 / 60 - mFittedCurveOffset, now() / 1000 / 60 - mFittedCurveOffset - (steps5Min + 6) * 5);
        long end = toTime / 1000 / 60 - mFittedCurveOffset;

        int nSteps = (int) (end - start);
        for (int i = 0; i <= nSteps; i++) {
            BgReading bg = new BgReading();
            bg.date = (start + i) * 60 * 1000 + mFittedCurveOffset * 60 * 1000;
            bg.value = value(start + i);

            mBgCurve.add(bg);
        }
        return mBgCurve;
    }


    // Initial guess of parameters, for provided points to be fitted
    abstract protected double[] getInitialGuess(List<WeightedObservedPoint> lObs);
}
