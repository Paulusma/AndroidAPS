package info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor;


class BGLow {
    public static final long NOT_FOUND = 1000L;

    private boolean isHypo;
    private long lowLevelMins;
    private double lowestBG;
    private long lowestBGMins;
    private String source;

    public String getSource() {
        return source;
    }

    long getLowLevelMins() {
        return lowLevelMins;
    }

    boolean isHypo() {return isHypo; }

    double getLowestBG() {
        return lowestBG;
    }

    long getLowestBGMins() {
        return lowestBGMins;
    }


    BGLow(String _source, boolean _isHypo, long _lowLevelMins, double _lowestBG, long _lowestBGMins){
        source = _source;
        lowLevelMins = _lowLevelMins;
        isHypo = _isHypo;
        lowestBG = _lowestBG;
        lowestBGMins = _lowestBGMins;
    }
}
