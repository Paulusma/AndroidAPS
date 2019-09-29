package info.nightscout.androidaps.plugins.general.stateviewer;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import com.j256.ormlite.android.apptools.OrmLiteSqliteOpenHelper;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.PreparedQuery;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.stmt.Where;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.utils.ToastUtils;

public class StateDBHelper extends OrmLiteSqliteOpenHelper {
    private static Logger log = LoggerFactory.getLogger(L.HGDPROV);

    public static final String DATABASE_NAME = "AndroidAPSDb";
    public static final String DATABASE_STATE = "StateData";

    private static final int DATABASE_VERSION = 11;

    public static Long earliestDataChange = null;

    private int oldVersion = 0;
    private int newVersion = 0;

    public StateDBHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        onCreate(getWritableDatabase(), getConnectionSource());
    }

    @Override
    public void onCreate(SQLiteDatabase database, ConnectionSource connectionSource) {
        try {
            if (L.isEnabled(L.DATABASE))
                log.info("onCreate");
            TableUtils.createTableIfNotExists(connectionSource, StateData.class);
        } catch (SQLException e) {
            log.error("Can't create database", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, ConnectionSource connectionSource, int oldVersion, int newVersion) {
        log.info("Do nothing for upgrading...");
        log.debug("oldVersion: {}, newVersion: {}", oldVersion, newVersion);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        log.info("Do nothing for downgrading...");
        log.debug("oldVersion: {}, newVersion: {}", oldVersion, newVersion);
    }

    public int getOldVersion() {
        return oldVersion;
    }

    public int getNewVersion() {
        return newVersion;
    }

    /**
     * Close the database connections and clear any cached DAOs.
     */
    @Override
    public void close() {
        super.close();
    }


    public long size(String database) {
        return DatabaseUtils.queryNumEntries(getReadableDatabase(), database);
    }

    // --------------------- DB resets ---------------------

    public void resetDatabases() {
        try {
            TableUtils.dropTable(connectionSource, StateData.class, true);
            TableUtils.createTableIfNotExists(connectionSource, StateData.class);
        } catch (SQLException e) {
            log.error("Unhandled exception", e);
        }
    }

    // ------------------ getDao -------------------------------------------

    private Dao<StateData, Long> getDaoStateData() throws SQLException {
        return getDao(StateData.class);
    }

    public static long roundDateToSec(long date) {
        long rounded = date - date % 1000;
        if (rounded != date)
            if (L.isEnabled(L.DATABASE))
                log.debug("Rounding " + date + " to " + rounded);
        return rounded;
    }

    public List<StateData> getStateData(long from, long to) {
        try {
            Dao<StateData, Long> dao = getDaoStateData();
            List<StateData> historicGraphData;
            QueryBuilder<StateData, Long> queryBuilder = dao.queryBuilder();
            queryBuilder.orderBy("date",true);
            Where where = queryBuilder.where();
            where.between("date", from, to);
            PreparedQuery<StateData> preparedQuery = queryBuilder.prepare();
            historicGraphData = dao.query(preparedQuery);
            return historicGraphData;
        } catch (SQLException e) {
            log.error("Unhandled exception", e);
        }
        return new ArrayList<StateData>();
    }

    public void createOrUpdateStateData(StateData historicGraphData) {
        try {
            Dao<StateData, Long> dao = getDaoStateData();
            dao.createOrUpdate(historicGraphData);
        } catch (SQLException e) {
            ToastUtils.showToastInUiThread(MainApp.instance(), "createOrUpdate-Exception");
            log.error("Unhandled exception", e);
        }
    }
}
