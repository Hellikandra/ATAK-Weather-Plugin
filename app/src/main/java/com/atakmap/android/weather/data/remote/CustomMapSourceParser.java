package com.atakmap.android.weather.data.remote;

import android.util.Xml;

import com.atakmap.coremap.log.Log;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.File;

/**
 * Parses customMapSource XML files (takkernel/MOBAC format) into tile source definitions.
 * Sprint 19 — S19.1
 *
 * Expected XML format:
 * <customMapSource>
 *   <name>Source Name</name>
 *   <minZoom>0</minZoom>
 *   <maxZoom>18</maxZoom>
 *   <tileType>png</tileType>
 *   <tileUpdate>None</tileUpdate>
 *   <url>{$z}/{$x}/{$y}</url>
 *   <backgroundColor>#000000</backgroundColor>
 * </customMapSource>
 */
public class CustomMapSourceParser {

    private static final String TAG = "CustomMapSourceParser";

    public static class TileSourceDef {
        public String name;
        public int minZoom = 0;
        public int maxZoom = 18;
        public String tileType = "png";
        public String tileUpdate = "None";
        public String urlTemplate;
        public String backgroundColor;

        /** Convert the {$z}/{$x}/{$y} URL template to standard {z}/{x}/{y} */
        public String resolveUrl(int z, int x, int y) {
            if (urlTemplate == null) return null;
            return urlTemplate
                .replace("{$z}", String.valueOf(z))
                .replace("{$x}", String.valueOf(x))
                .replace("{$y}", String.valueOf(y));
        }

        public boolean isValid() {
            return name != null && urlTemplate != null;
        }

        @Override
        public String toString() {
            return "TileSourceDef{name='" + name + "', zoom=" + minZoom + "-" + maxZoom +
                   ", type=" + tileType + ", url=" + urlTemplate + "}";
        }
    }

    /**
     * Parse a customMapSource XML file.
     * @param file The XML file to parse
     * @return TileSourceDef or null if parsing failed
     */
    public static TileSourceDef parse(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return parse(fis);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse tile source: " + file.getName(), e);
            return null;
        }
    }

    /**
     * Parse a customMapSource XML from an InputStream.
     */
    public static TileSourceDef parse(InputStream is) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(is, "UTF-8");

            TileSourceDef def = new TileSourceDef();
            String currentTag = null;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        currentTag = parser.getName();
                        break;
                    case XmlPullParser.TEXT:
                        if (currentTag != null) {
                            String text = parser.getText().trim();
                            switch (currentTag) {
                                case "name": def.name = text; break;
                                case "minZoom": def.minZoom = parseInt(text, 0); break;
                                case "maxZoom": def.maxZoom = parseInt(text, 18); break;
                                case "tileType": def.tileType = text; break;
                                case "tileUpdate": def.tileUpdate = text; break;
                                case "url": def.urlTemplate = text; break;
                                case "backgroundColor": def.backgroundColor = text; break;
                            }
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        currentTag = null;
                        break;
                }
                eventType = parser.next();
            }

            return def.isValid() ? def : null;
        } catch (Exception e) {
            Log.e(TAG, "XML parse error", e);
            return null;
        }
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return fallback; }
    }
}
