package org.dtakc.weather.atak.data.remote.openmeteo;
public final class OpenMeteoECMWFSource extends OpenMeteoDataSource {
    public static final String SOURCE_ID = "open-meteo-ecmwf";
    @Override public String getSourceId()    { return SOURCE_ID; }
    @Override public String getDisplayName() { return "Open-Meteo ECMWF"; }
    @Override protected String model()       { return "&models=ecmwf_ifs04"; }
}
