#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Weather Tool",
   plugin-version: "3.1.1",
   platform: "ATAK",
   platform-version: "5.6.0",
)


#tak-slide[
  = Overview
#toolbox.side-by-side(columns: (.75fr, 9fr))[
#image("plugin_icon.png", width: 70%)
][
The *Weather Tool* plugin brings forecast, marine and aviation weather into ATAK. It shows conditions at your position, at the map centre or at any point you pick, and draws weather on the map itself -- precipitation radar, parameter heatmaps, wind flow, ocean currents, SIGMET areas and a CBRN dispersion model.
]
= Key Capabilities
- *Current conditions and 7-day forecast* -- at self, map centre, or any picked point
- *Hourly detail* -- scrubbable chart with zoom and configurable range
- *Wind* -- vertical wind profile, wind rose, and a wind-effect cone
- *Map overlays* -- radar, heatmap, aviation SIGMETs, CBRN plume, marine currents
- *Markers* -- drop weather and wind markers, share them over the TAK network
- *Route weather* -- conditions sampled along any ATAK route
- *Multiple providers* -- Open-Meteo GFS, ECMWF, DWD ICON, and FAA aviation observations
- *Offline capable* -- forecasts cached locally; mission prep pre-fetches for later
- *Unit control* -- metric, imperial or aviation presets, or set each unit individually
]

#tak-slide[
  = Installing
== Requirements
ATAK *5.6.0* (CIV). The plugin ships native libraries for *arm64-v8a* and *armeabi-v7a*.

== Upgrading from version 1.x
#text(weight: "bold")[Uninstall the old plugin first.] Versions 1.x and 3.x are signed with different keys, and Android refuses to replace an installed app when the signature differs, whatever the version number. You will see `INSTALL_FAILED_UPDATE_INCOMPATIBLE` if you skip this.

Uninstalling removes cached forecasts and radar tiles. Your unit preferences are stored by ATAK and survive.

== Installing
Install the APK as you would any ATAK plugin, then start ATAK and accept the plugin load prompt. Open *Weather* from the ATAK menu.
]

#tak-slide[
  = The Panel
The plugin opens as a drop-down panel with a navigation bar across the top. Five icons switch between sections; the back arrow returns to the summary.

== Summary
The landing view. Current conditions, a 7-day forecast, and the location header showing which point the reading is for -- *Self*, *Map centre*, or a picked point.

Two badges appear here when relevant:
- *Staleness* -- how old the displayed reading is
- *Offline* -- shown when the last fetch failed and you are seeing cached data

The reading also names the provider that actually answered, next to the timestamp. This matters when a source serves part of its data from elsewhere -- see *Data Sources*.

== Weather
Detailed conditions plus an hourly chart. Drag the slider to scrub through the forecast hours; the detail rows follow. The chart supports zoom and a selectable range, and individual parameters can be toggled on and off.
]

#tak-slide[
  = Wind
== Wind Profile
A vertical profile showing wind speed and direction at several altitudes. With the aviation source selected this is built from real winds-aloft observations; with the Open-Meteo sources it comes from model pressure levels.

== Wind Rose
Direction and speed distribution for the selected location.

== Wind Effect
Draws a cone on the map from a chosen point, showing where wind is carrying from or towards. Useful for smoke, dust and scent-line reasoning.

== Wind Particles and Arrows
The Overlays section can draw the wind field directly on the map, either as arrows on a grid or as animated flow particles.

#text(style: "italic")[The particle layer needs the native library and therefore an arm device. On x86 devices and emulators it renders nothing.]
]

#tak-slide[
  = Overlays
Five sub-panels, selected with the pills at the top of the section.

== Radar
Precipitation radar tiles from RainViewer, with playback across recent frames, opacity, colour and brightness controls. Tiles are cached on the device, so revisiting an area does not re-download. Other tile sources can be added -- see *Adding Your Own Source*.

== Heatmap
Colours the map by a chosen parameter -- temperature, humidity, pressure, wind and others -- interpolated across the visible area. Opacity and colour scale are adjustable.

== Aviation
SIGMET and AIRMET hazard areas from the FAA Aviation Weather Center, drawn as polygons.

== CBRN
A Gaussian plume dispersion model for a release at a chosen point, using Pasquill-Gifford stability classes derived from current conditions. The plume follows the forecast wind over several hours.

#text(weight: "bold")[This is a planning aid, not an authoritative dispersion product.] It carries a disclaimer in the panel and should be treated as indicative.

== Marine
Ocean current flow and wave data for coastal and open water, with a coastline mask so currents are only drawn over water.
]

#tak-slide[
  = Markers and Routes
== Weather and Wind Markers
Drop a marker at a picked point and it carries the weather for that location. Choose the type -- weather or wind -- then tap *Drop Marker* and pick a point on the map.

Markers appear under *Weather* in the ATAK Overlay Manager, and can be shared over the TAK network like any other map item. Incoming weather markers from other users are received automatically.

== Route Weather
Select an existing ATAK route and fetch conditions along it. The plugin samples up to twenty points spread along the route and shows a list plus a chart of wind and humidity, so you can see where conditions change along the way.
]

#tak-slide[
  = Settings and Units
== Sources
Choose which provider supplies weather data, and see what each one covers. The source manager lists every available source, shows whether it is active, and can test connectivity.

== Parameters
Pick which weather parameters are fetched and displayed. Fewer parameters means smaller, faster requests.

== Auto-refresh
Set an interval for automatic refresh, or leave it off to fetch only on demand.

== Mission Prep
Pre-fetch forecasts for an area so they are available later without a network. Useful before going into a degraded or disconnected environment.

== Cache
Clear cached radar tiles, heatmap data or recorded forecast history.

== Units
Units live in *ATAK Settings #sym.arrow.r Tool Preferences #sym.arrow.r Weather Tool*, not in the plugin panel.

Three presets are available -- *Metric* (#sym.degree\C, m/s, hPa), *Imperial* (#sym.degree\F, mph, inHg) and *Aviation* (#sym.degree\C, knots, inHg, statute miles, feet). Each unit can also be set individually, and an individual choice survives a later preset change.
]

#tak-slide[
  = Data Sources
The plugin ships with four built-in providers. None requires an API key or an account.

== Open-Meteo (GFS) -- default
Global forecast model. Current conditions, hourly and daily forecasts, wind profile.

== Open-Meteo (ECMWF)
ECMWF model with pressure-level winds. Often better for upper-air work.

== Open-Meteo (DWD ICON)
High-resolution model over Europe.

== Aviation Weather Center (METAR)
Real surface observations from airport stations, plus winds-aloft forecast tiers. Reports flight category (VFR / MVFR / IFR / LIFR) and the raw METAR string.

#text(weight: "bold")[Note:] the AWC does not publish a gridded forecast, so when this source is selected the *daily and hourly forecast tabs are served by Open-Meteo instead*. The panel states this, and each reading names the provider that produced it.

== Other data
Radar tiles come from RainViewer, marine data from Open-Meteo's marine API, aviation hazards from the FAA, and place names from OpenStreetMap Nominatim.

== Rate limits
Open-Meteo limits requests to roughly ten a minute. The plugin de-duplicates identical requests and backs off automatically, but rapid panning with several overlays enabled can still hit the limit. It recovers on its own.
]

#tak-slide[
  = Adding Your Own Source
Sources are defined in JSON, so a new provider can be added without rebuilding the plugin.

Put a `.json` file in:
#align(center)[`/sdcard/atak/tools/weather_sources/`]

Then open *Settings #sym.arrow.r Sources* and tap refresh. The new source appears in the spinner immediately.

== Weather source
A weather definition needs `sourceId`, `displayName` and `apiBaseUrl`, plus lists of the parameters to request. Copy `TEMPLATE_weather_source_v2.json` from the plugin's bundled sources as a starting point.

== Radar source
A radar definition needs `radarSourceId`, `displayName`, `manifestUrl` and a `tileUrlTemplate`. The template accepts the placeholders `{timestamp}`, `{z}`, `{x}`, `{y}` and `{size}`, which is the standard slippy-map tile scheme.

== Overriding a built-in
Use the same `sourceId` as a bundled source and your file takes precedence. Files are loaded from the plugin's own assets first, then from the folder above, so user files always win.

#text(style: "italic")[Definitions must be JSON. The earlier YAML format was removed in v3.1.1 because its parser could not handle common YAML structures and failed silently.]
]

#tak-slide[
  = Known Limitations
Read this before relying on any of the following.

== Lightning
#text(weight: "bold")[There is no live lightning feed.] The overlay is present but reports "no live source" and displays nothing. Earlier versions generated simulated strikes for testing; that has been removed, because fabricated strikes on a tactical map are worse than no layer at all. A real provider is being evaluated.

== Wind particles on x86
The particle engine is native code shipped for arm only. On x86 devices and emulators the layer draws nothing. Wind arrows work everywhere.

== Aviation forecasts
As described under *Data Sources*, the AWC source serves Open-Meteo model output for daily and hourly forecasts. The panel says so, and each reading names its provider.

== Heatmap legend
A colour-scale legend exists but has no control to display it yet.

== CBRN model
A planning aid using a standard Gaussian plume approximation. Not an authoritative dispersion product.
]

#tak-slide[
  = Troubleshooting
== The map goes black when I open the plugin
Fixed in *v3.1.1*. Earlier versions painted the host map surface opaque when the plugin theme was applied. If you see this, you are on an older build -- update.

== The plugin will not install
If you have version 1.x installed, uninstall it first. See *Installing*.

== Weather stops updating
Usually the provider's rate limit. Wait a minute; the plugin backs off and recovers. Switching source or disabling some overlays reduces request volume.

== Units reset when ATAK restarts
Fixed in *v3.1.1*. If it persists, capture `adb logcat` output covering an ATAK start and a unit change, and open an issue.

== Nothing appears for a picked point
Check the location header on the Summary view -- it names the point the reading is for. If the plugin cannot reach the network it shows cached data with an *Offline* badge.

== Reporting a problem
Issues: `github.com/Hellikandra/ATAK-Weather-Plugin/issues`

Please include your ATAK version, the plugin version from the panel, the device, and `adb logcat` output if the plugin crashed or failed to load.
]
