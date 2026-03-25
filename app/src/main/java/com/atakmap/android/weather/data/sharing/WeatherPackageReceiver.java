package com.atakmap.android.weather.data.sharing;

import android.content.Context;
import android.widget.Toast;

import com.atakmap.android.weather.data.remote.SourceFileImporter;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Handles receiving weather source files via ATAK MissionPackage / Data Package system.
 * Sprint 19 — S19.4
 *
 * Integration pattern:
 * 1. Register as a MissionPackageReceiver in WeatherMapComponent
 * 2. When a data package containing weather_sources/*.json or weather_tiles/*.xml arrives,
 *    auto-import using SourceFileImporter
 * 3. Notify user that new sources are available
 *
 * Sending pattern:
 * 1. User selects a source file in Settings > Sources
 * 2. "Share" wraps the file in a MissionPackage manifest
 * 3. Uses MissionPackageApi.send() to transmit to selected contacts/groups
 */
public class WeatherPackageReceiver {

    private static final String TAG = "WeatherPackageReceiver";

    /** MIME type for weather source packages */
    public static final String WEATHER_PACKAGE_TYPE = "application/x-weather-source";

    /** Package manifest content type markers */
    public static final String CONTENT_WEATHER_SOURCE = "weather-source-definition";
    public static final String CONTENT_TILE_SOURCE = "weather-tile-source";

    private final Context context;

    public WeatherPackageReceiver(Context context) {
        this.context = context;
    }

    /**
     * Called when a MissionPackage is received that contains weather source files.
     * Extracts and imports each source file.
     *
     * @param packageDir The extracted package directory
     * @return number of sources successfully imported
     */
    public int onPackageReceived(File packageDir) {
        int imported = 0;

        if (packageDir == null || !packageDir.isDirectory()) {
            Log.w(TAG, "Invalid package directory");
            return 0;
        }

        // Look for JSON weather sources
        File[] jsonFiles = packageDir.listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
        if (jsonFiles != null) {
            for (File f : jsonFiles) {
                if (SourceFileImporter.importFile(context, f)) {
                    imported++;
                }
            }
        }

        // Look for XML tile sources
        File[] xmlFiles = packageDir.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
        if (xmlFiles != null) {
            for (File f : xmlFiles) {
                if (SourceFileImporter.importFile(context, f)) {
                    imported++;
                }
            }
        }

        if (imported > 0) {
            Toast.makeText(context, "Imported " + imported + " weather source(s) from data package",
                Toast.LENGTH_LONG).show();
        }

        return imported;
    }

    /**
     * Prepare a source file for sharing via MissionPackage.
     * Creates a package manifest and returns the package directory path.
     *
     * @param sourceFile The source file to share
     * @return File path of the created package directory, or null on failure
     */
    public File preparePackageForSharing(File sourceFile) {
        if (sourceFile == null || !sourceFile.exists()) {
            Log.w(TAG, "Cannot share: file does not exist");
            return null;
        }

        try {
            // Create temp package directory
            File packageDir = new File(context.getCacheDir(),
                    "weather_package_" + System.currentTimeMillis());
            if (!packageDir.mkdirs()) {
                Log.e(TAG, "Cannot create package directory");
                return null;
            }

            // Copy source file into package
            File packagedFile = new File(packageDir, sourceFile.getName());
            copyFile(sourceFile, packagedFile);

            // Create manifest
            File manifest = new File(packageDir, "MANIFEST.xml");
            String contentType = sourceFile.getName().toLowerCase().endsWith(".xml")
                    ? CONTENT_TILE_SOURCE : CONTENT_WEATHER_SOURCE;
            String manifestContent = buildManifest(sourceFile.getName(), contentType);
            writeTextFile(manifest, manifestContent);

            Log.i(TAG, "Package prepared: " + packageDir.getAbsolutePath());
            return packageDir;

        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare package", e);
            return null;
        }
    }

    private String buildManifest(String fileName, String contentType) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<MissionPackageManifest version=\"2\">\n" +
               "  <Configuration>\n" +
               "    <Parameter name=\"name\" value=\"Weather Source: " + fileName + "\"/>\n" +
               "    <Parameter name=\"uid\" value=\"weather-pkg-" + System.currentTimeMillis() + "\"/>\n" +
               "  </Configuration>\n" +
               "  <Contents>\n" +
               "    <Content zipEntry=\"" + fileName + "\">\n" +
               "      <Parameter name=\"contentType\" value=\"" + contentType + "\"/>\n" +
               "    </Content>\n" +
               "  </Contents>\n" +
               "</MissionPackageManifest>\n";
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private static void writeTextFile(File file, String content) throws IOException {
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(content);
        }
    }
}
