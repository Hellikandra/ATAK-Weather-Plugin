package com.atakmap.android.weather.data.cache;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.atakmap.android.weather.data.cache.entity.DailyEntry;
import com.atakmap.android.weather.data.cache.entity.HourlyEntry;
import com.atakmap.android.weather.data.cache.entity.WeatherSnapshot;

/**
 * Room database — single file "weather_cache.db" in the plugin's data dir.
 *
 * Schema version: 1
 * Future migrations: add a static Migration constant here and pass it to
 * addMigrations() in the builder. Never use fallbackToDestructiveMigration()
 * in production — user's cached history would be lost.
 *
 * Lifecycle: created once per plugin process via getInstance(); the singleton
 * is closed in WeatherMapComponent.onDestroyComponent().
 */
@Database(
        entities = {WeatherSnapshot.class, HourlyEntry.class, DailyEntry.class},
        version  = 1,
        exportSchema = false
)
public abstract class WeatherDatabase extends RoomDatabase {

    private static volatile WeatherDatabase INSTANCE;
    private static final String DB_NAME = "weather_cache.db";

    public abstract WeatherDao weatherDao();

    public static WeatherDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (WeatherDatabase.class) {
                if (INSTANCE == null) {
                    // Defensively resolve the Application context.
                    // In the ATAK plugin sandbox, pluginContext.getApplicationContext()
                    // returns null; callers must pass mapView.getContext() which is
                    // the host app context and has a valid ApplicationContext.
                    Context appCtx = context.getApplicationContext();
                    if (appCtx == null) appCtx = context;  // last-resort fallback
                    INSTANCE = Room.databaseBuilder(
                                    appCtx,
                                    WeatherDatabase.class,
                                    DB_NAME)
                            .allowMainThreadQueries()
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    /** Call from WeatherMapComponent.onDestroyComponent() to release resources. */
    public static void destroyInstance() {
        if (INSTANCE != null && INSTANCE.isOpen()) {
            INSTANCE.close();
        }
        INSTANCE = null;
    }
}
