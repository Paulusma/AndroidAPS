package info.nightscout.androidaps.plugins.general.bgmonitors.hypopredictor;


class BGLow {
    public static final long NONE = 1000L;

    private long lowLevelMins;
    private long alertLevelMins;
    private double lowestBG;
    private long lowestBGMins;
    private String source;

    public String getSource() {
        return source;
    }

    long getLowLevelMins() {
        return lowLevelMins;
    }

    long getAlertLevelMins() {
        return alertLevelMins;
    }

    double getLowestBG() {
        return lowestBG;
    }

    public long getLowestBGMins() {
        return lowestBGMins;
    }


    BGLow(String _source, long _lowLevelMins, long _alertLevelMins, double _lowestBG, long _lowestBGMins){
        source = _source;
        lowLevelMins = _lowLevelMins;
        alertLevelMins = _alertLevelMins;
        lowestBG = _lowestBG;
        lowestBGMins = _lowestBGMins;
    }
}
