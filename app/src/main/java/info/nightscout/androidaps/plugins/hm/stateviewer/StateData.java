package info.nightscout.androidaps.plugins.hm.stateviewer;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.logging.L;

@DatabaseTable(tableName = DatabaseHelper.DATABASE_STATE)
public class StateData {
    private static Logger log = LoggerFactory.getLogger(L.DATABASE);

    @DatabaseField(id = true)
    public long date;

    @DatabaseField
    public double bg;

    @DatabaseField
    public double activity;

    @DatabaseField
    public double basal;

    @DatabaseField
    public boolean isTempBasalRunning;

    @DatabaseField
    public double tempBasalAbsolute;

    @DatabaseField
    public double iob;

    @DatabaseField
    public double cob;

    @DatabaseField
    public boolean failoverToMinAbsorbtionRate;

    @DatabaseField
    public double carbsFromBolus;

    @DatabaseField
    public double deviation;

    @DatabaseField
    public String pastSensitivity;

    @DatabaseField
    public String type;

    @DatabaseField
    public double sens;

    @DatabaseField
    public double slopeMin;

    @DatabaseField
    public double slopeMax;

    @DatabaseField
    public double target;

    public StateData() {
        this.date = 0L;
        this.bg = 0.0;
        this.activity = 0.0;
        this.basal = 0.0;
        this.isTempBasalRunning = false;
        this.tempBasalAbsolute = 0.0;
        this.iob = 0.0;
        this.cob = 0.0;
        this.failoverToMinAbsorbtionRate = false;
        this.carbsFromBolus = 0.0;
        this.deviation = 0.0;
        this.pastSensitivity = "";
        this.type = "";
        this.sens = 0.0;
        this.slopeMin = 0.0;
        this.slopeMax = 0.0;
        this.target = 0.0;   }

    @Override
    public String toString() {
        return "HistoricGraphData{" +
                "date=" + date +
                ", bg=" + bg +
                ", activity=" + activity +
                ", basal=" + basal +
                ", isTempBasalRunning=" + isTempBasalRunning +
                ", tempBasalAbsolute=" + tempBasalAbsolute +
                ", iob=" + iob +
                ", cob=" + cob +
                ", carbsFromBolus=" + carbsFromBolus +
                ", failoverToMinAbsorbtionRate=" + failoverToMinAbsorbtionRate +
                ", deviation=" + deviation +
                ", pastSensitivity=" + pastSensitivity +
                ", type=" + type +
                ", sens=" + sens +
                ", slopeMin=" + slopeMin +
                ", slopeMax=" + slopeMax +
                ", target=" + target +
                '}';
    }
}
