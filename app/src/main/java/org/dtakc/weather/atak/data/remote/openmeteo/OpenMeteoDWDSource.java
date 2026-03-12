package org.dtakc.weather.atak.data.remote.openmeteo;
public final class OpenMeteoDWDSource extends OpenMeteoDataSource {
    public static final String SOURCE_ID = "open-meteo-dwd";
    @Override public String getSourceId()    { return SOURCE_ID; }
    @Override public String getDisplayName() { return "Open-Meteo DWD ICON"; }
    @Override protected String model()       { return "&models=icon_seamless"; }
}
