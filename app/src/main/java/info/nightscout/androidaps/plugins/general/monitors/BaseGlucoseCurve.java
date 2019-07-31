package info.nightscout.androidaps.plugins.general.monitors;

import java.util.*;
import org.apache.commons.math3.analysis.ParametricUnivariateFunction;
import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;

public class BaseGlucoseCurve implements ParametricUnivariateFunction {

    private double[] parameters = null;
    double[] getParameters(){
        return parameters;
    }

    // Actual time, in msecs since midnight January 1 1970 UTC, of the time zero point
    // All points are relative to this zero-point (thus keeping time values small to limit inherent rounding errors in computations)
    private long timeOffset = 0;
    long getTimeOffset(){
        return timeOffset;
    }

    public double value(double t, double... parameters) {
        return parameters[0] * Math.pow(t, parameters[1]) * Math.exp(-parameters[2] * t);
    }

    // Jacobian matrix of the above. In this case, this is just an array of
    // partial derivatives of the above function, with one element for each parameter.
    public double[] gradient(double t, double... parameters) {
        final double a = parameters[0];
        final double b = parameters[1];
        final double c = parameters[2];

        // Jacobian Matrix Edit

        // Using Derivative Structures...
        // constructor takes 4 arguments - the number of parameters in your
        // equation to be differentiated (3 in this case), the order of
        // differentiation for the DerivativeStructure, the index of the
        // parameter represented by the DS, and the value of the parameter itself
        DerivativeStructure aDev = new DerivativeStructure(3, 1, 0, a);
        DerivativeStructure bDev = new DerivativeStructure(3, 1, 1, b);
        DerivativeStructure cDev = new DerivativeStructure(3, 1, 2, c);

        // define the equation to be differentiated using another DerivativeStructure
        DerivativeStructure y = aDev.multiply(DerivativeStructure.pow(t, bDev))
                .multiply(cDev.negate().multiply(t).exp());

        // then return the partial derivatives required
        // notice the format, 3 arguments for the method since 3 parameters were
        // specified first order derivative of the first parameter, then the second,
        // then the third
        return new double[] {
                y.getPartialDerivative(1, 0, 0),
                y.getPartialDerivative(0, 1, 0),
                y.getPartialDerivative(0, 0, 1)
        };
    }
}