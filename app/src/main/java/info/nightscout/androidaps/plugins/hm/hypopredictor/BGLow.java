package info.nightscout.androidaps.plugins.hm.hypopredictor;


class BGLow {
    private boolean isHypo;
    private long lowLevelMins;
    private double lowestBG;
    private long lowestBGMins;
    private String source;
    private double bgAt30Min;
    private double bgAt60Min;

    public String getSource() {
        return source;
    }

    long getLowLevelMins() {
        return lowLevelMins;
    }

    public double getBgAt30Min() {
        return bgAt30Min;
    }

    public double getBgAt60Min() {
        return bgAt60Min;
    }

    boolean isHypo() {return isHypo; }

    double getLowestBG() {
        return lowestBG;
    }

    long getLowestBGMins() {
        return lowestBGMins;
    }


    BGLow(String _source, boolean _isHypo, long _lowLevelMins, double _lowestBG, long _lowestBGMins, double _bgAt30Min, double _bgAt60Min){
        source = _source;
        lowLevelMins = _lowLevelMins;
        isHypo = _isHypo;
        lowestBG = _lowestBG;
        lowestBGMins = _lowestBGMins;
        bgAt30Min = _bgAt30Min;
        bgAt60Min=_bgAt60Min;
    }
}
