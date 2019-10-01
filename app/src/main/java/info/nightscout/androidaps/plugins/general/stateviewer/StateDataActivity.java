package info.nightscout.androidaps.plugins.general.stateviewer;

import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.overview.OverviewPlugin;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.AreaGraphSeries;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.DataPointWithLabelInterface;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.DoubleDataPoint;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.PointsWithLabelGraphSeries;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.Scale;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.ScaledDataPoint;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.TimeAsXAxisLabelFormatter;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.MidnightTime;

public class StateDataActivity extends AppCompatActivity {
    private static Logger log = LoggerFactory.getLogger(L.HGDPROV);

    ImageButton chartButton;

    @BindView(R.id.statebrowse_graph)
    GraphView stateGraph;
    @BindView(R.id.statebrowse_noprofile)
    TextView noProfile;
    @BindView(R.id.statebrowse_export)
    Button buttonExport;
    SeekBar seekBar;
    TextView seekProgress;

    private long timePeriod = 7*24*60*60*1000;
    private int timeWindow = 24*60*60*1000;
    private long fromTime = 0;
    ScaledDataPoint[] bgData = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_statebrowser);

            ButterKnife.bind(this);
            stateGraph.getViewport().setScalable(true);
            stateGraph.getViewport().setScrollable(true);
            stateGraph.getViewport().setMaxX(System.currentTimeMillis());
            stateGraph.getViewport().setMinX(System.currentTimeMillis() - 3 * 60 * 60 * 1000);
            stateGraph.getViewport().setXAxisBoundsManual(true);


            // set a change listener on the SeekBar
            seekProgress = findViewById(R.id.statebrowse_progress);
            seekProgress.setText(DateUtil.dateString(MidnightTime.calc(System.currentTimeMillis())));
            seekBar = findViewById(R.id.statebrowser_seekBar);
            seekBar.setMax((int)(timePeriod/timeWindow));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){

                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                    long date = fromTime +progress * timeWindow;
                    seekProgress.setText(DateUtil.dateString(MidnightTime.calc(date)));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {

                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {

                }
            });
        } catch (Exception e) {
            log.info(e.getMessage());
            log.error("Unhandled exception", e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        MainApp.bus().unregister(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        MainApp.bus().register(this);
        updateGUI();
    }

    @OnClick(R.id.statebrowse_export)
    void onClickExport() {
//TODO
    }

    void updateGUI() {
        log.debug("updateGUI");

        try {
            final Profile profile = ProfileFunctions.getInstance().getProfile();

            if (profile == null) {
                noProfile.setVisibility(View.VISIBLE);
                return;
            } else {
                noProfile.setVisibility(View.GONE);
            }
            final String units = profile.getUnits();
            final double lowLine = OverviewPlugin.getPlugin().determineLowLine(units);
            final double highLine = OverviewPlugin.getPlugin().determineHighLine(units);

            final long toTime;
            toTime = System.currentTimeMillis();
            fromTime = toTime - timePeriod;


            //  ------------------ Statedata graph
            List<StateData> stateData =StateDataPlugin.getPlugin().loadData(fromTime,toTime);
            if (stateData == null || stateData.size() == 0)
                return;

            stateGraph.getSeries().clear();
            addInRangeArea(fromTime, toTime, lowLine, highLine);
            int maxY = addStateData(fromTime, toTime, lowLine, highLine,stateData);
            addBasals(fromTime, toTime, maxY,stateData);
            addTargetLine(fromTime, toTime,stateData);
            addTreatments(fromTime, toTime);

            // ------------------ todo navigator

        } catch (Exception e) {
            log.info(e.getMessage());
            log.error("Unhandled exception", e);
        }
    }

    public int addStateData(long fromTime, long toTime, double lowLine, double highLine, List<StateData> stateData) {
        double maxBg = Double.MIN_VALUE, minBg;
        double minIOB = 0, maxIOB = 0;
        double maxCOB = 0;
        double minAct = 0, maxAct = 0;
        double minRat = 0, maxRat = 0;

        final Profile profile = ProfileFunctions.getInstance().getProfile();

        bgData = new ScaledDataPoint[stateData.size()];
        ScaledDataPoint[] iobData = new ScaledDataPoint[stateData.size()];
        ScaledDataPoint[] cobData = new ScaledDataPoint[stateData.size()];
        ScaledDataPoint[] ratData = new ScaledDataPoint[stateData.size()];
        ScaledDataPoint[] actData = new ScaledDataPoint[stateData.size()];

        Scale bgScale = new Scale();
        Scale iobScale = new Scale();
        Scale cobScale = new Scale();
        Scale ratScale = new Scale();
        Scale actScale = new Scale();

        int ndx = 0;
        for (StateData state : stateData) {
            double bg =  Profile.fromMgdlToUnits(state.bg, profile.getUnits());
            double ratio = (state.sens-1.0d);
            bgData[ndx] = new ScaledDataPoint(state.date, bg, bgScale);
            iobData[ndx] = new ScaledDataPoint(state.date, state.iob, iobScale);
            cobData[ndx] = new ScaledDataPoint(state.date, state.cob, cobScale);
            ratData[ndx] = new ScaledDataPoint(state.date, ratio, ratScale);
            actData[ndx] = new ScaledDataPoint(state.date, state.activity, actScale);

            ndx++;
        }

        LineGraphSeries<ScaledDataPoint> bgSeries = new LineGraphSeries<>(bgData);
        LineGraphSeries<ScaledDataPoint> iobSeries = new LineGraphSeries<>(iobData);
        LineGraphSeries<ScaledDataPoint> cobSeries = new LineGraphSeries<>(cobData);
        LineGraphSeries<ScaledDataPoint> ratSeries = new LineGraphSeries<>(ratData);
        LineGraphSeries<ScaledDataPoint> actSeries = new LineGraphSeries<>(actData);

        maxBg = (profile.getUnits().equals(Constants.MGDL) ? (int) 300 : (int) 16);
        minBg = -(profile.getUnits().equals(Constants.MGDL) ? (int) 40 : (int) 2);
        maxAct = 0.05;
        maxIOB = 10;
        maxCOB = 50;
        maxRat = 0.5;

        bgScale.setMultiplier(1);
        iobScale.setMultiplier(-minBg / (maxIOB > -minIOB ? maxIOB : -minIOB));
        cobScale.setMultiplier(-minBg / maxCOB);
        ratScale.setMultiplier(-minBg / (maxRat > -minRat ? maxRat : -minRat));
        actScale.setMultiplier(maxBg / maxAct);

        // Series-specific settings

        // BG
        bgSeries.setThickness(3);

        // IOB
//        iobSeries.setDrawBackground(true);
//        iobSeries.setBackgroundColor(0x80FFFFFF & MainApp.gc(R.color.iob)); //50%
        iobSeries.setColor(MainApp.gc(R.color.iob));
        iobSeries.setThickness(1);

        // COB
//        cobSeries.setDrawBackground(true);
//        cobSeries.setBackgroundColor(0x80FFFFFF & MainApp.gc(R.color.cob)); //50%
        cobSeries.setColor(MainApp.gc(R.color.cob));
        cobSeries.setThickness(1);

        //Ratio
        ratSeries.setColor(MainApp.gc(R.color.ratio));
        ratSeries.setThickness(1);

        // Activity
        actSeries.setDrawBackground(false);
        actSeries.setColor(MainApp.gc(R.color.activity));
        actSeries.setThickness(1);

        int numOfVertLines = profile.getUnits().equals(Constants.MGDL) ? (int) ((maxBg-minBg) / 40 + 1) : (int) ((maxBg-minBg) / 2 + 1);
        stateGraph.getGridLabelRenderer().setNumVerticalLabels(numOfVertLines);
        stateGraph.getViewport().setMaxY(maxBg);
        stateGraph.getViewport().setMinY(minBg);
        stateGraph.getViewport().setYAxisBoundsManual(true);

        stateGraph.getGridLabelRenderer().setGridColor(MainApp.gc(R.color.graphgrid));
        stateGraph.getGridLabelRenderer().reloadStyles();
        stateGraph.getGridLabelRenderer().setLabelVerticalWidth(50);
        stateGraph.getGridLabelRenderer().setLabelFormatter(new TimeAsXAxisLabelFormatter("HH:mm"));
        stateGraph.getGridLabelRenderer().setNumHorizontalLabels(4);

        stateGraph.addSeries(bgSeries);
        stateGraph.addSeries(iobSeries);
        stateGraph.addSeries(cobSeries);
        stateGraph.addSeries(ratSeries);
        stateGraph.addSeries(actSeries);

        return (int)maxBg;
    }

    public void addInRangeArea(long fromTime, long toTime, double lowLine, double highLine) {
        AreaGraphSeries<DoubleDataPoint> inRangeAreaSeries;

        DoubleDataPoint[] inRangeAreaDataPoints = new DoubleDataPoint[]{
                new DoubleDataPoint(fromTime, lowLine, highLine),
                new DoubleDataPoint(toTime, lowLine, highLine)
        };
        inRangeAreaSeries = new AreaGraphSeries<>(inRangeAreaDataPoints);
        inRangeAreaSeries.setColor(0);
        inRangeAreaSeries.setDrawBackground(true);
        inRangeAreaSeries.setBackgroundColor(MainApp.gc(R.color.inrangebackground));

        stateGraph.addSeries(inRangeAreaSeries);
    }

    private double getNearestBg(long date) {
        final Profile profile = ProfileFunctions.getInstance().getProfile();
        if (bgData == null)
            return Profile.fromMgdlToUnits(100, profile.getUnits());
        for (int r = 0; r < bgData.length; r++) {
            ScaledDataPoint point = bgData[r];
            if (point.getX() < date || point.getY() == 0) continue;
            return point.getY();
        }
        return bgData.length > 0
                ? bgData[0].getY() : Profile.fromMgdlToUnits(100, profile.getUnits());
    }

    public void addTreatments(long fromTime, long endTime) {
        List<DataPointWithLabelInterface> filteredTreatments = new ArrayList<>();

        StateDataPlugin gdp = StateDataPlugin.getPlugin();
        List<Treatment> treatments = gdp.getTreatments(fromTime, endTime);

        for (int tx = 0; tx < treatments.size(); tx++) {
            Treatment t = treatments.get(tx);
            if (t.isSMB && !t.isValid) continue;
            t.setY(getNearestBg((long) t.getX()));
            filteredTreatments.add(t);
        }

        // ProfileSwitch
        List<ProfileSwitch> profileSwitches = gdp.getProfileSwitches(fromTime, endTime);

        for (int tx = 0; tx < profileSwitches.size(); tx++) {
            DataPointWithLabelInterface t = profileSwitches.get(tx);
            filteredTreatments.add(t);
        }

        // Extended bolus
        if (!ConfigBuilderPlugin.getPlugin().getActivePump().isFakingTempsByExtendedBoluses()) {
            List<ExtendedBolus> extendedBoluses = gdp.getExtendedBoluses(fromTime,endTime);

            for (int tx = 0; tx < extendedBoluses.size(); tx++) {
                DataPointWithLabelInterface t = extendedBoluses.get(tx);
                if (t.getDuration() == 0) continue;
                t.setY(getNearestBg((long) t.getX()));
                filteredTreatments.add(t);
            }
        }

        // Careportal
        List<CareportalEvent> careportalEvents = gdp.getcareportalEvents(fromTime,endTime);

        for (int tx = 0; tx < careportalEvents.size(); tx++) {
            DataPointWithLabelInterface t = careportalEvents.get(tx);
            t.setY(getNearestBg((long) t.getX()));
            filteredTreatments.add(t);
        }

        DataPointWithLabelInterface[] treatmentsArray = new DataPointWithLabelInterface[filteredTreatments.size()];
        treatmentsArray = filteredTreatments.toArray(treatmentsArray);
        stateGraph.addSeries(new PointsWithLabelGraphSeries<>(treatmentsArray));
    }


    // scale in % of vertical size (like 0.3)
    public void addBasals(long fromTime, long toTime, int maxY, List<StateData> stateData) {
        LineGraphSeries<ScaledDataPoint> basalsLineSeries;
        LineGraphSeries<ScaledDataPoint> absoluteBasalsLineSeries;
        LineGraphSeries<ScaledDataPoint> baseBasalsSeries;
        LineGraphSeries<ScaledDataPoint> tempBasalsSeries;

        double maxBasalValueFound = 0d;
        Scale basalScale = new Scale();

        List<ScaledDataPoint> baseBasalArray = new ArrayList<>();
        List<ScaledDataPoint> tempBasalArray = new ArrayList<>();
        List<ScaledDataPoint> basalLineArray = new ArrayList<>();
        List<ScaledDataPoint> absoluteBasalLineArray = new ArrayList<>();
        double lastLineBasal = 0;
        double lastAbsoluteLineBasal = -1;
        double lastBaseBasal = 0;
        double lastTempBasal = 0;

        StateDataPlugin gdp = StateDataPlugin.getPlugin();
        int ndx = 0;
        for (StateData state : stateData) {
            double baseBasalValue = state.basal;
            double absoluteLineValue = baseBasalValue;
            double tempBasalValue = 0;
            double basal = 0d;
            if (state.isTempBasalRunning) {
                absoluteLineValue = tempBasalValue = state.tempBasalAbsolute;
                if (tempBasalValue != lastTempBasal) {
                    tempBasalArray.add(new ScaledDataPoint(state.date, lastTempBasal, basalScale));
                    tempBasalArray.add(new ScaledDataPoint(state.date, basal = tempBasalValue, basalScale));
                }
                if (lastBaseBasal != 0d) {
                    baseBasalArray.add(new ScaledDataPoint(state.date, lastBaseBasal, basalScale));
                    baseBasalArray.add(new ScaledDataPoint(state.date, 0d, basalScale));
                    lastBaseBasal = 0d;
                }
            } else {
                if (baseBasalValue != lastBaseBasal) {
                    baseBasalArray.add(new ScaledDataPoint(state.date, lastBaseBasal, basalScale));
                    baseBasalArray.add(new ScaledDataPoint(state.date, basal = baseBasalValue, basalScale));
                    lastBaseBasal = baseBasalValue;
                }
                if (lastTempBasal != 0) {
                    tempBasalArray.add(new ScaledDataPoint(state.date, lastTempBasal, basalScale));
                    tempBasalArray.add(new ScaledDataPoint(state.date, 0d, basalScale));
                }
            }

            if (baseBasalValue != lastLineBasal) {
                basalLineArray.add(new ScaledDataPoint(state.date, lastLineBasal, basalScale));
                basalLineArray.add(new ScaledDataPoint(state.date, baseBasalValue, basalScale));
            }
            if (absoluteLineValue != lastAbsoluteLineBasal) {
                absoluteBasalLineArray.add(new ScaledDataPoint(state.date, lastAbsoluteLineBasal, basalScale));
                absoluteBasalLineArray.add(new ScaledDataPoint(state.date, basal, basalScale));
            }

            lastAbsoluteLineBasal = absoluteLineValue;
            lastLineBasal = baseBasalValue;
            lastTempBasal = tempBasalValue;
            maxBasalValueFound = Math.max(maxBasalValueFound, Math.max(tempBasalValue, baseBasalValue));

            ndx++;
        }

        basalLineArray.add(new ScaledDataPoint(toTime, lastLineBasal-2, basalScale));
        baseBasalArray.add(new ScaledDataPoint(toTime, lastBaseBasal-2, basalScale));
        tempBasalArray.add(new ScaledDataPoint(toTime, lastTempBasal-2, basalScale));
        absoluteBasalLineArray.add(new ScaledDataPoint(toTime, lastAbsoluteLineBasal-2, basalScale));

        ScaledDataPoint[] baseBasal = new ScaledDataPoint[baseBasalArray.size()];
        baseBasal = baseBasalArray.toArray(baseBasal);
        baseBasalsSeries = new LineGraphSeries<>(baseBasal);
        baseBasalsSeries.setDrawBackground(true);
        baseBasalsSeries.setBackgroundColor(MainApp.gc(R.color.basebasal));
        baseBasalsSeries.setThickness(0);

        ScaledDataPoint[] tempBasal = new ScaledDataPoint[tempBasalArray.size()];
        tempBasal = tempBasalArray.toArray(tempBasal);
        tempBasalsSeries = new LineGraphSeries<>(tempBasal);
        tempBasalsSeries.setDrawBackground(true);
        tempBasalsSeries.setBackgroundColor(MainApp.gc(R.color.tempbasal));
        tempBasalsSeries.setThickness(0);

        ScaledDataPoint[] basalLine = new ScaledDataPoint[basalLineArray.size()];
        basalLine = basalLineArray.toArray(basalLine);
        basalsLineSeries = new LineGraphSeries<>(basalLine);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(MainApp.instance().getApplicationContext().getResources().getDisplayMetrics().scaledDensity * 2);
        paint.setPathEffect(new DashPathEffect(new float[]{2, 4}, 0));
        paint.setColor(MainApp.gc(R.color.basal));
        basalsLineSeries.setCustomPaint(paint);

        ScaledDataPoint[] absoluteBasalLine = new ScaledDataPoint[absoluteBasalLineArray.size()];
        absoluteBasalLine = absoluteBasalLineArray.toArray(absoluteBasalLine);
        absoluteBasalsLineSeries = new LineGraphSeries<>(absoluteBasalLine);
        Paint absolutePaint = new Paint();
        absolutePaint.setStyle(Paint.Style.STROKE);
        absolutePaint.setStrokeWidth(MainApp.instance().getApplicationContext().getResources().getDisplayMetrics().scaledDensity * 2);
        absolutePaint.setColor(MainApp.gc(R.color.basal));
        absoluteBasalsLineSeries.setCustomPaint(absolutePaint);

        basalScale.setMultiplier(1);

        stateGraph.addSeries(baseBasalsSeries);
        stateGraph.addSeries(tempBasalsSeries);
        stateGraph.addSeries(basalsLineSeries);
        stateGraph.addSeries(absoluteBasalsLineSeries);
    }

    public void addTargetLine(long fromTime, long toTime, List<StateData> stateData) {
        final Profile profile = ProfileFunctions.getInstance().getProfile();
        LineGraphSeries<DataPoint> targetsSeries;

        Scale targetsScale = new Scale();
        targetsScale.setMultiplier(1);

        List<DataPoint> targetsSeriesArray = new ArrayList<>();
        double lastTarget = -1;
        int ndx = 0;
        for (StateData state : stateData) {
            long time = state.date;
            double tt = state.target;
            double value;
            if (tt == 0.0d) {
                value = (profile.getTargetLow(time) + profile.getTargetHigh(time)) / 2;
            } else {
                value = Profile.fromMgdlToUnits(tt, profile.getUnits());
            }
            if (lastTarget != value) {
                if (lastTarget != -1)
                    targetsSeriesArray.add(new DataPoint(time, lastTarget));
                targetsSeriesArray.add(new DataPoint(time, value));
            }
            lastTarget = value;
        }
        targetsSeriesArray.add(new DataPoint(toTime, lastTarget));

        DataPoint[] targets = new DataPoint[targetsSeriesArray.size()];
        targets = targetsSeriesArray.toArray(targets);
        targetsSeries = new LineGraphSeries<>(targets);
        targetsSeries.setDrawBackground(false);
        targetsSeries.setColor(MainApp.gc(R.color.tempTargetBackground));
        targetsSeries.setThickness(2);

        stateGraph.addSeries(targetsSeries);
    }
}
