package com.atakmap.android.weather.data.remote.schema;

/**
 * Configuration describing how to parse a radar manifest response.
 * Field names correspond to JSON keys in the manifest document
 * (e.g., RainViewer's weather-maps.json).
 */
public class ManifestParsingConfig {

    private final String hostField;
    private final String versionField;
    private final String generatedField;
    private final String pastPath;
    private final String futurePath;
    private final String timeField;
    private final String pathField;

    public ManifestParsingConfig(String hostField, String versionField,
                                  String generatedField, String pastPath,
                                  String futurePath, String timeField,
                                  String pathField) {
        this.hostField = hostField;
        this.versionField = versionField;
        this.generatedField = generatedField;
        this.pastPath = pastPath;
        this.futurePath = futurePath;
        this.timeField = timeField;
        this.pathField = pathField;
    }

    public String getHostField() { return hostField; }
    public String getVersionField() { return versionField; }
    public String getGeneratedField() { return generatedField; }
    public String getPastPath() { return pastPath; }
    public String getFuturePath() { return futurePath; }
    public String getTimeField() { return timeField; }
    public String getPathField() { return pathField; }
}
