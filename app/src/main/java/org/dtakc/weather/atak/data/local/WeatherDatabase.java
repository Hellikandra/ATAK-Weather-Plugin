package org.dtakc.weather.atak.data.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import org.dtakc.weather.atak.data.local.entity.DailyEntry;
import org.dtakc.weather.atak.data.local.entity.HourlyEntry;
import org.dtakc.weather.atak.data.local.entity.WeatherSnapshot;

/**
 * Room database singleton for the weather cache.
 * destroyInstance() is called from WeatherPluginComponent.onDestroyImpl()
 * to ensure a hot-swap reinstall gets a clean database handle (ISS-02).
 */
@Database(entities = {WeatherSnapshot.class, HourlyEntry.class, DailyEntry.class},
          version  = 1, exportSchema = false)
public abstract class WeatherDatabase extends RoomDatabase {

    private static volatile WeatherDatabase INSTANCE;
    private static final   String DB_NAME = "dtakc_weather_cache.db";

    public abstract WeatherDao weatherDao();

    public static WeatherDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (WeatherDatabase.class) {
                if (INSTANCE == null) {
                    Context appCtx = context.getApplicationContext();
                    if (appCtx == null) appCtx = context;
                    INSTANCE = Room.databaseBuilder(appCtx, WeatherDatabase.class, DB_NAME)
                            .allowMainThreadQueries()   // DAO calls already dispatched via executor
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    /** Called on plugin destroy — ensures hot-swap gets a fresh handle (fixes ISS-02). */
    public static void destroyInstance() {
        synchronized (WeatherDatabase.class) {
            if (INSTANCE != null && INSTANCE.isOpen()) INSTANCE.close();
            INSTANCE = null;
        }
    }
}
