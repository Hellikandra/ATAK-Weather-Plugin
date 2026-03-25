package com.atakmap.android.weather.data.remote;

import android.content.Context;
import android.widget.Toast;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Imports weather/radar source definition files (JSON or XML) into the plugin's
 * user source directories.
 * Sprint 19 — S19.2
 */
public class SourceFileImporter {

    private static final String TAG = "SourceFileImporter";

    // User-writable source directories on sdcard
    private static final String WEATHER_SOURCES_DIR = "/sdcard/atak/tools/weather_sources/";
    private static final String TILE_SOURCES_DIR = "/sdcard/atak/tools/weather_tiles/";

    /**
     * Import a source file. Determines type by extension:
     * - .json -> weather_sources/ (v2 JSON format)
     * - .xml -> weather_tiles/ (customMapSource XML)
     *
     * @return true if import succeeded
     */
    public static boolean importFile(Context context, File sourceFile) {
        if (sourceFile == null || !sourceFile.exists()) {
            Log.w(TAG, "Source file does not exist");
            return false;
        }

        String name = sourceFile.getName().toLowerCase();
        String targetDir;

        if (name.endsWith(".json")) {
            targetDir = WEATHER_SOURCES_DIR;
        } else if (name.endsWith(".xml")) {
            // Validate it's a customMapSource XML before importing
            CustomMapSourceParser.TileSourceDef def = CustomMapSourceParser.parse(sourceFile);
            if (def == null) {
                Log.w(TAG, "XML file is not a valid customMapSource: " + name);
                Toast.makeText(context, "Invalid XML tile source: " + name, Toast.LENGTH_SHORT).show();
                return false;
            }
            targetDir = TILE_SOURCES_DIR;
        } else {
            Log.w(TAG, "Unsupported file type: " + name);
            Toast.makeText(context, "Unsupported file type. Use .json or .xml", Toast.LENGTH_SHORT).show();
            return false;
        }

        // Ensure target directory exists
        File dir = new File(targetDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create directory: " + targetDir);
            return false;
        }

        // Copy file to target directory
        File targetFile = new File(dir, sourceFile.getName());
        try {
            copyFile(sourceFile, targetFile);
            Log.i(TAG, "Imported source: " + targetFile.getAbsolutePath());
            Toast.makeText(context, "Imported: " + sourceFile.getName(), Toast.LENGTH_SHORT).show();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy source file", e);
            Toast.makeText(context, "Import failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /**
     * List all user-imported weather source files (.json).
     */
    public static File[] listUserWeatherSources() {
        File dir = new File(WEATHER_SOURCES_DIR);
        if (!dir.exists()) return new File[0];
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
        return files != null ? files : new File[0];
    }

    /**
     * List all user-imported tile source files (.xml).
     */
    public static File[] listUserTileSources() {
        File dir = new File(TILE_SOURCES_DIR);
        if (!dir.exists()) return new File[0];
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
        return files != null ? files : new File[0];
    }

    /**
     * Delete a user-imported source file.
     */
    public static boolean deleteSource(File file) {
        if (file != null && file.exists()) {
            return file.delete();
        }
        return false;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}
