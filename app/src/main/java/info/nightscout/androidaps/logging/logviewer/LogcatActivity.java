package info.nightscout.androidaps.logging.logviewer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.util.List;

import javax.annotation.Nullable;

import info.nightscout.androidaps.R;
import info.nightscout.androidaps.logging.L;

public class LogcatActivity extends AppCompatActivity {
    private static Logger log = LoggerFactory.getLogger("LogcatActivity");

    public static void launch(Context context) {
        context.startActivity(new Intent(context, LogcatActivity.class));
    }

    private static final int REQUEST_SCREEN_OVERLAY = 23453;

    private View mRoot;
    private ListView mList;

    private LogcatAdapter mAdapter = new LogcatAdapter();
    private boolean mReading = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().setNavigationBarColor(Color.parseColor("#1a1a1a"));
            }
            setContentView(R.layout.activity_logcat);
            mRoot = findViewById(R.id.root);
            Toolbar toolbar = findViewById(R.id.toolbar);
            Spinner spinner = findViewById(R.id.spinner);
            mList = findViewById(R.id.list);

            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }

            ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(this,
                    R.array.logcat_spinner, R.layout.item_logcat_dropdown);
            spinnerAdapter.setDropDownViewResource(R.layout.item_logcat_dropdown);
            spinner.setAdapter(spinnerAdapter);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    String filter = getResources().getStringArray(R.array.logcat_spinner)[position];
                    mAdapter.getFilter().filter(filter);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {

                }
            });

            mList.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
            mList.setStackFromBottom(true);
            mList.setAdapter(mAdapter);
            mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    LogcatDetailActivity.launch(LogcatActivity.this, mAdapter.getItem(position));
                }
            });
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.logcat, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.clear) {
            mAdapter.clear();
            return true;
        } else if (item.getItemId() == R.id.export) {
            @SuppressLint("StaticFieldLeak")
            ExportLogFileTask task = new ExportLogFileTask(getExternalCacheDir()) {
                @Override
                protected void onPostExecute(File file) {
                    if (file == null) {
                        Snackbar.make(mRoot, R.string.create_log_file_failed, Snackbar.LENGTH_SHORT)
                                .show();
                    } else {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        Uri uri = LogcatFileProvider.getUriForFile(getApplicationContext(),
                                getPackageName() + ".logcat_fileprovider", file);
                        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                        if (getPackageManager().queryIntentActivities(
                                shareIntent, 0).isEmpty()) {
                            Snackbar.make(mRoot, R.string.not_support_on_this_device,
                                    Snackbar.LENGTH_SHORT).show();
                        } else {
                            startActivity(shareIntent);
                        }
                    }
                }
            };
            task.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, mAdapter.getData());
            return true;
        } else if (item.getItemId() == R.id.floating) {
            Context context = getApplicationContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                if (getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                    Snackbar.make(mRoot, R.string.not_support_on_this_device,
                            Snackbar.LENGTH_SHORT).show();
                } else {
                    startActivityForResult(intent, REQUEST_SCREEN_OVERLAY);
                }
            } else {
                FloatingLogcatService.launch(context);
                finish();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_OVERLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Settings.canDrawOverlays(getApplicationContext())) {
            FloatingLogcatService.launch(getApplicationContext());
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startReadLogcat();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopReadLogcat();
    }

    // sample:
    // 10-21 16:01:46.539  1949  2233 I NetworkController.MobileSignalController(2):  showDisableIcon:false
    private void startReadLogcat() {
        new Thread() {
            @Override
            public void run() {
                super.run();
                mReading = true;
                BufferedReader reader = null;
                try {
                    Process process = new ProcessBuilder("logcat", "-v", "threadtime").start();
                    reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while (mReading && (line = reader.readLine()) != null) {
                        if (LogItem.IGNORED_LOG.contains(line)) {
                            continue;
                        }
                        if (!LineFromEnabled(line)) {
                            continue;
                        }
                        try {
                            final LogItem item = new LogItem(line);
                            mList.post(new Runnable() {
                                @Override
                                public void run() {
                                    mAdapter.append(item);
                                }
                            });
                        } catch (ParseException | NumberFormatException | IllegalStateException e) {
                            e.printStackTrace();
                        }
                    }
                    stopReadLogcat();
                } catch (IOException e) {
                    e.printStackTrace();
                    stopReadLogcat();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.start();
    }

    private boolean LineFromEnabled(String line) {
        boolean bAllEltsDisabled = true;
        List<L.LogElement> logElts = L.getLogElements();
        for (L.LogElement logElt : logElts
        ) {
            if (!logElt.enabled) continue;

            bAllEltsDisabled = false;
            if (line.contains(logElt.name))
                return true;
        }

        return bAllEltsDisabled;
    }

    private void stopReadLogcat() {
        mReading = false;
    }
}
