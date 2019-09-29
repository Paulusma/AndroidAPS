package info.nightscout.androidaps.plugins.general.stateviewer;

import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.PopupMenu;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Date;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.overview.OverviewFragment;
import info.nightscout.androidaps.plugins.general.overview.OverviewPlugin;
import info.nightscout.androidaps.plugins.general.overview.graphData.GraphData;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.TimeAsXAxisLabelFormatter;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;
import info.nightscout.androidaps.utils.T;

public class StateDataActivity extends AppCompatActivity {
    private static Logger log = LoggerFactory.getLogger(StateDataActivity.class);

    IobCobCalculatorPlugin iobCobCalculatorPlugin;

    ImageButton chartButton;

    boolean showBasal = true;

    StateDataPlugin dataProvider = StateDataPlugin.getPlugin();

    Button buttonDate;
    Button buttonZoom;
    GraphView bgGraph;
    GraphView iobGraph;
    SeekBar seekBar;
    TextView noProfile;
    TextView iobCalculationProgressView;

    private int rangeToDisplay = 24; // for graph
    private long start = 0;

    public StateDataActivity() {
        iobCobCalculatorPlugin = new IobCobCalculatorPlugin();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_historybrowse);

        buttonDate = findViewById(R.id.historybrowse_date);
        buttonZoom = findViewById(R.id.historybrowse_zoom);
        bgGraph = findViewById(R.id.historyybrowse_bggraph);
        iobGraph = findViewById(R.id.historybrowse_iobgraph);
        seekBar = findViewById(R.id.historybrowse_seekBar);
        noProfile = findViewById(R.id.historybrowse_noprofile);
        iobCalculationProgressView = findViewById(R.id.overview_iobcalculationprogess);

        findViewById(R.id.historybrowse_left).setOnClickListener(v -> {
            start -= T.hours(rangeToDisplay).msecs();
            updateGUI("onClickLeft");
        });

        findViewById(R.id.historybrowse_right).setOnClickListener(v -> {
            start += T.hours(rangeToDisplay).msecs();
            updateGUI("onClickRight");
        });

        findViewById(R.id.historybrowse_end).setOnClickListener(v -> {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            start = calendar.getTimeInMillis();
            updateGUI("onClickEnd");
        });

        findViewById(R.id.historybrowse_zoom).setOnClickListener(v -> {
            rangeToDisplay += 6;
            rangeToDisplay = rangeToDisplay > 24 ? 6 : rangeToDisplay;
            updateGUI("rangeChange");
        });

        findViewById(R.id.historybrowse_zoom).setOnLongClickListener(v -> {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(start);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            start = calendar.getTimeInMillis();
            updateGUI("resetToMidnight");
            return true;
        });

        findViewById(R.id.historybrowse_date).setOnClickListener(v -> {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new Date(start));
            DatePickerDialog dpd = DatePickerDialog.newInstance(
                    (view, year, monthOfYear, dayOfMonth) -> {
                        Date date = new Date(0);
                        date.setYear(year - 1900);
                        date.setMonth(monthOfYear);
                        date.setDate(dayOfMonth);
                        date.setHours(0);
                        start = date.getTime();
                        updateGUI("onClickDate");
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
            );
            dpd.setThemeDark(true);
            dpd.dismissOnPause(true);
            dpd.show(getFragmentManager(), "Datepickerdialog");
        });

        bgGraph.getGridLabelRenderer().setGridColor(MainApp.gc(R.color.graphgrid));
        bgGraph.getGridLabelRenderer().reloadStyles();
        iobGraph.getGridLabelRenderer().setGridColor(MainApp.gc(R.color.graphgrid));
        iobGraph.getGridLabelRenderer().reloadStyles();
        iobGraph.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        bgGraph.getGridLabelRenderer().setLabelVerticalWidth(50);
        iobGraph.getGridLabelRenderer().setLabelVerticalWidth(50);
        iobGraph.getGridLabelRenderer().setNumVerticalLabels(5);

        bgGraph.getViewport().setScrollable(true); // enables horizontal scrolling
        bgGraph.getViewport().setScalable(true); // enables horizontal zooming and scrolling

        setupChartMenu();
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
        // set start of current day
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        start = calendar.getTimeInMillis();
        SystemClock.sleep(1000);
        updateGUI("onResume");
    }

    void updateGUI(String from) {
        log.debug("updateGUI from: " + from);
        try {
            if (noProfile == null || buttonDate == null || buttonZoom == null || bgGraph == null || iobGraph == null || seekBar == null)
                return;

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

            buttonDate.setText(DateUtil.dateAndTimeString(start));
            buttonZoom.setText(String.valueOf(rangeToDisplay));

            int hoursToFetch;
            final long toTime;
            final long fromTime;
            fromTime = start + T.secs(100).msecs();
            toTime = start + T.hours(rangeToDisplay).msecs();

            log.debug("Period: " + DateUtil.dateAndTimeString(fromTime) + " - " + DateUtil.dateAndTimeString(toTime));

            final long pointer = System.currentTimeMillis();

            //  ------------------ 1st graph

            final GraphData graphData = new GraphData(bgGraph);

            // **** In range Area ****
            graphData.addInRangeArea(fromTime, toTime, lowLine, highLine);

            // **** BG ****
                graphData.addBgReadings(fromTime, toTime, lowLine, highLine, null, dataProvider);

            // set manual x bounds to have nice steps
//            graphData.formatAxis(fromTime, toTime);
            bgGraph.getViewport().setMaxX(fromTime+6*60*60*1000);
            bgGraph.getViewport().setMinX(fromTime);
            bgGraph.getViewport().setXAxisBoundsManual(true);
            bgGraph.getGridLabelRenderer().setLabelFormatter(new TimeAsXAxisLabelFormatter("HH"));
            bgGraph.getGridLabelRenderer().setNumHorizontalLabels(7); // only 7 because of the space

            if(SP.getBoolean("showactivityprimary", true)) {
                graphData.addActivity(fromTime, toTime, false, 1d, dataProvider);
            }

            // Treatments
            graphData.addTreatments(fromTime, toTime, dataProvider);

            // add basal data
            if (SP.getBoolean("showhbasals", true)) {
                graphData.addBasals(fromTime, toTime, lowLine / graphData.maxY / 1.2d, dataProvider);
            }

            // add target line
            graphData.addTargetLine(fromTime, toTime, profile,dataProvider);

            // **** NOW line ****
            graphData.addNowLine(pointer);

            // ------------------ 2nd graph

            new Thread(() -> {
                final GraphData secondGraphData = new GraphData(iobGraph);

                boolean useIobForScale = false;
                boolean useCobForScale = false;
                boolean useDevForScale = false;
                boolean useRatioForScale = false;
                boolean useIAForScale = false;
                boolean useDSForScale = false;

                if (SP.getBoolean("showhiob", true)) {
                    useIobForScale = true;
                } else if (SP.getBoolean("showhcob", true)) {
                    useCobForScale = true;
                } else if (SP.getBoolean("showhdeviations", false)) {
                    useDevForScale = true;
                } else if (SP.getBoolean("showhratios", false)) {
                    useRatioForScale = true;
                } else if (SP.getBoolean("showhactivitysecondary", false)) {
                    useIAForScale = true;
                } else if (SP.getBoolean("showhdevslope", false)) {
                    useDSForScale = true;
                }

                if (SP.getBoolean("showhiob", true))
                    secondGraphData.addIob(fromTime, toTime, useIobForScale, 1d,dataProvider);
                if (SP.getBoolean("showhcob", true))
                    secondGraphData.addCob(fromTime, toTime, useCobForScale, useCobForScale ? 1d : 0.5d,dataProvider);
                if (SP.getBoolean("showhdeviations", false))
                    secondGraphData.addDeviations(fromTime, toTime, useDevForScale, 1d,dataProvider);
                if (SP.getBoolean("showhratios", false))
                    secondGraphData.addRatio(fromTime, toTime, useRatioForScale, 1d,dataProvider);
                if(SP.getBoolean("showhactivitysecondary", true))
                    secondGraphData.addActivity(fromTime, toTime, useIAForScale,useIAForScale ? 2d: 1d,dataProvider);
                if (SP.getBoolean("showhdevslope", false) && MainApp.devBranch)
                    secondGraphData.addDeviationSlope(fromTime, toTime, useDSForScale, 1d,dataProvider);

                // **** NOW line ****
                // set manual x bounds to have nice steps
                secondGraphData.formatAxis(fromTime, toTime);
                secondGraphData.addNowLine(pointer);

                // do GUI update
                runOnUiThread(() -> {
                    if (SP.getBoolean("showhiob", true)
                            || SP.getBoolean("showhcob", true)
                            || SP.getBoolean("showheviations", false)
                            || SP.getBoolean("showhratios", false)
                            || SP.getBoolean("showhactivitysecondary", false)
                            || SP.getBoolean("showhdevslope", false)) {
                        iobGraph.setVisibility(View.VISIBLE);
                    } else {
                        iobGraph.setVisibility(View.GONE);
                    }
                    // finally enforce drawing of graphs
                    graphData.performUpdate();
                    if (SP.getBoolean("showhiob", true)
                            || SP.getBoolean("showhcob", true)
                            || SP.getBoolean("showheviations", false)
                            || SP.getBoolean("showhratios", false)
                            || SP.getBoolean("showhactivitysecondary", false)
                            || SP.getBoolean("showhdevslope", false))
                        secondGraphData.performUpdate();
                });
            }).start();
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    private void setupChartMenu() {
        chartButton = (ImageButton) findViewById(R.id.overview_chartMenuButton);
        chartButton.setOnClickListener(v -> {
            MenuItem item,dividerItem;
            CharSequence title;
            int titleMaxChars = 0;
            SpannableString s;
            android.support.v7.widget.PopupMenu popup = new PopupMenu(v.getContext(), v);


            item = popup.getMenu().add(Menu.NONE, OverviewFragment.CHARTTYPE.BAS.ordinal(), Menu.NONE, MainApp.gs(R.string.overview_show_basals));
            title = item.getTitle();
            if (titleMaxChars < title.length()) titleMaxChars =  title.length();
            s = new SpannableString(title);
            s.setSpan(new ForegroundColorSpan(ResourcesCompat.getColor(getResources(), R.color.basal, null)), 0, s.length(), 0);
            item.setTitle(s);
            item.setCheckable(true);
            item.setChecked(SP.getBoolean("showhbasals", true));

            item = popup.getMenu().add(Menu.NONE, OverviewFragment.CHARTTYPE.ACTPRIM.ordinal(), Menu.NONE, MainApp.gs(R.string.overview_show_activity));
            title = item.getTitle();
            if (titleMaxChars < title.length()) titleMaxChars =  title.length();
            s = new SpannableString(title);
            s.setSpan(new ForegroundColorSpan(ResourcesCompat.getColor(getResources(), R.color.activity, null)), 0, s.length(), 0);
            item.setTitle(s);
            item.setCheckable(true);
            item.setChecked(SP.getBoolean("showhactivityprimary", true));

            dividerItem = popup.getMenu().add("");
            dividerItem.setEnabled(false);

            item = popup.getMenu().add(Menu.NONE, OverviewFragment.CHARTTYPE.IOB.ordinal(), Menu.NONE, MainApp.gs(R.string.overview_show_iob));
            title = item.getTitle();
            if (titleMaxChars < title.length()) titleMaxChars =  title.length();
            s = new SpannableString(title);
            s.setSpan(new ForegroundColorSpan(ResourcesCompat.getColor(getResources(), R.color.iob, null)), 0, s.length(), 0);
            item.setTitle(s);
            item.setCheckable(true);
            item.setChecked(SP.getBoolean("showhiob", true));

            item = popup.getMenu().add(Menu.NONE, OverviewFragment.CHARTTYPE.COB.ordinal(), Menu.NONE, MainApp.gs(R.string.overview_show_cob));
            title = item.getTitle();
            if (titleMaxChars < title.length()) titleMaxChars =  title.length();
            s = new SpannableString(title);
            s.setSpan(new ForegroundColorSpan(ResourcesCompat.getColor(getResources(), R.color.cob, null)), 0, s.length(), 0);
            item.setTitle(s);
            item.setCheckable(true);
            item.setChecked(SP.getBoolean("showhcob", true));

            item = popup.getMenu().add(Menu.NONE, OverviewFragment.CHARTTYPE.DEV.ordinal(), Menu.NONE, MainApp.gs(R.string.overview_show_deviations));
            title = item.getTitle();
            if (titleMaxChars < title.length()) titleMaxChars =  title.length();
            s = new SpannableString(title);
            s.setSpan(new ForegroundColorSpan(ResourcesCompat.getColor(getResources(), R.color.deviations, null)), 0, s.length(), 0);
            item.setTitle(s);
            item.setCheckable(true);
            item.setChecked(SP.getBoolean("showhdeviations", false));

            item = popup.getMenu().add(Menu.NONE, OverviewFragment.CHARTTYPE.SEN.ordinal(), Menu.NONE, MainApp.gs(R.string.overview_show_sensitivity));
            title = item.getTitle();
            if (titleMaxChars < title.length()) titleMaxChars =  title.length();
            s = new SpannableString(title);
            s.setSpan(new ForegroundColorSpan(ResourcesCompat.getColor(getResources(), R.color.ratio, null)), 0, s.length(), 0);
            item.setTitle(s);
            item.setCheckable(true);
            item.setChecked(SP.getBoolean("showhratios", false));

            item = popup.getMenu().add(Menu.NONE, OverviewFragment.CHARTTYPE.ACTSEC.ordinal(), Menu.NONE, MainApp.gs(R.string.overview_show_activity));
            title = item.getTitle();
            if (titleMaxChars < title.length()) titleMaxChars =  title.length();
            s = new SpannableString(title);
            s.setSpan(new ForegroundColorSpan(ResourcesCompat.getColor(getResources(), R.color.activity, null)), 0, s.length(), 0);
            item.setTitle(s);
            item.setCheckable(true);
            item.setChecked(SP.getBoolean("showhactivitysecondary", true));


            if (MainApp.devBranch) {
                item = popup.getMenu().add(Menu.NONE, OverviewFragment.CHARTTYPE.DEVSLOPE.ordinal(), Menu.NONE, "Deviation slope");
                title = item.getTitle();
                if (titleMaxChars < title.length()) titleMaxChars =  title.length();
                s = new SpannableString(title);
                s.setSpan(new ForegroundColorSpan(ResourcesCompat.getColor(getResources(), R.color.devslopepos, null)), 0, s.length(), 0);
                item.setTitle(s);
                item.setCheckable(true);
                item.setChecked(SP.getBoolean("showhdevslope", false));
            }

            // Fairly good guestimate for required divider text size...
            title = new String(new char[titleMaxChars+10]).replace("\0", "_");
            dividerItem.setTitle(title);

            popup.setOnMenuItemClickListener(item1 -> {
                if (item1.getItemId() == OverviewFragment.CHARTTYPE.BAS.ordinal()) {
                    SP.putBoolean("showhbasals", !item1.isChecked());
                } else if (item1.getItemId() == OverviewFragment.CHARTTYPE.IOB.ordinal()) {
                    SP.putBoolean("showhiob", !item1.isChecked());
                } else if (item1.getItemId() == OverviewFragment.CHARTTYPE.COB.ordinal()) {
                    SP.putBoolean("showhcob", !item1.isChecked());
                } else if (item1.getItemId() == OverviewFragment.CHARTTYPE.DEV.ordinal()) {
                    SP.putBoolean("showhdeviations", !item1.isChecked());
                } else if (item1.getItemId() == OverviewFragment.CHARTTYPE.SEN.ordinal()) {
                    SP.putBoolean("showhratios", !item1.isChecked());
                } else if (item1.getItemId() == OverviewFragment.CHARTTYPE.ACTPRIM.ordinal()) {
                    SP.putBoolean("showhactivityprimary", !item1.isChecked());
                } else if (item1.getItemId() == OverviewFragment.CHARTTYPE.ACTSEC.ordinal()) {
                    SP.putBoolean("showhactivitysecondary", !item1.isChecked());
                } else if (item1.getItemId() == OverviewFragment.CHARTTYPE.DEVSLOPE.ordinal()) {
                    SP.putBoolean("showhdevslope", !item1.isChecked());
                }
                updateGUI("onGraphCheckboxesCheckedChanged");
                return true;
            });
            chartButton.setImageResource(R.drawable.ic_arrow_drop_up_white_24dp);
            popup.setOnDismissListener(menu -> chartButton.setImageResource(R.drawable.ic_arrow_drop_down_white_24dp));
            popup.show();
        });
    }

}
