# ATAK Weather Plugin

Forecast, marine and aviation weather inside ATAK — on the panel and on the map.

**Current release: v3.1.1** · ATAK 5.6.0 (CIV) · arm64-v8a and armeabi-v7a

---

## What it does

Shows conditions at your position, at the map centre, or at any point you pick — and
draws weather on the map itself.

| | |
|---|---|
| **Summary** | Current conditions and a 7-day forecast, with staleness and offline badges |
| **Weather** | Detailed conditions and a scrubbable hourly chart with zoom and range |
| **Wind** | Vertical wind profile, wind rose, and a wind-effect cone on the map |
| **Overlays** | Precipitation radar, parameter heatmap, aviation SIGMETs, CBRN plume, marine currents |
| **Markers** | Weather and wind markers, shareable over the TAK network; weather along a route |
| **Settings** | Sources, parameters, auto-refresh, mission-prep pre-fetch, cache management |

Forecasts are cached on the device, so the plugin keeps working when the network
does not. Mission Prep pre-fetches an area before you go out.

## Installing

Download the APK from [Releases](https://github.com/Hellikandra/ATAK-Weather-Plugin/releases),
install it as you would any ATAK plugin, then open **Weather** from the ATAK menu.

> **Upgrading from v1.x — uninstall the old plugin first.**
> v1.x and v3.x are signed with different keys, and Android refuses to replace an
> installed app when the signature differs, whatever the version number. You will
> otherwise see `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Data sources

Four providers are built in. **None needs an API key or an account.**

| Source | Provides |
|---|---|
| **Open-Meteo (GFS)** — default | Global forecast model: current, hourly, daily, wind profile |
| **Open-Meteo (ECMWF)** | ECMWF model with pressure-level winds |
| **Open-Meteo (DWD ICON)** | High-resolution model over Europe |
| **Aviation Weather Center** | Real METAR station observations, winds aloft, flight category |

Radar tiles come from RainViewer, marine data from Open-Meteo's marine API, aviation
hazards from the FAA, and place names from OpenStreetMap Nominatim.

Each reading names the provider that actually produced it, next to the timestamp.
That matters for the AWC source: the AWC publishes no gridded forecast, so its daily
and hourly tabs are served by Open-Meteo instead, and the panel says so.

Open-Meteo rate-limits to roughly ten requests a minute. The plugin de-duplicates
identical requests and backs off automatically.

## Adding your own source

Sources are defined in JSON, so a provider can be added without rebuilding. Drop a
`.json` file in `/sdcard/atak/tools/weather_sources/`, then **Settings → Sources → refresh**.

Templates and a reference schema ship inside the plugin under
`assets/weather_sources/` — start from `TEMPLATE_weather_source_v2.json` or
`TEMPLATE_radar_source.json`. Radar tile URLs use the standard slippy-map placeholders
`{timestamp}` `{z}` `{x}` `{y}` `{size}`. Reuse a bundled `sourceId` to override it.

Definitions must be JSON. The earlier YAML format was removed in v3.1.1 — its
hand-rolled parser could not handle common YAML structures and failed silently
([#19](https://github.com/Hellikandra/ATAK-Weather-Plugin/issues/19)).

## Known limitations

- **No live lightning feed.** The overlay reports "no live source" and shows nothing.
  Earlier builds generated simulated strikes; that was removed, because fabricated
  strikes on a tactical map are worse than no layer at all
  ([#23](https://github.com/Hellikandra/ATAK-Weather-Plugin/issues/23)).
- **Wind particles need an arm device.** The native engine ships for arm only; on x86
  the particle layer draws nothing. Wind arrows work everywhere.
- **The CBRN plume is a planning aid**, using a standard Gaussian approximation with
  Pasquill-Gifford stability classes. Not an authoritative dispersion product.
- The heatmap colour legend exists but has no control to show it yet
  ([#25](https://github.com/Hellikandra/ATAK-Weather-Plugin/issues/25)).

## Documentation

The full user manual ships inside the plugin as `assets/usermanual.pdf` and is built
from [`docs/user_manual/`](docs/user_manual/) by `gradle/typst.gradle` during the
release build. To rebuild it locally with [Typst](https://typst.app) installed:

```
typst compile --root . --font-path docs/user_manual \
  docs/user_manual/usermanual.typ app/src/main/assets/usermanual.pdf
```

## Building

```
./gradlew assembleCivDebug          # debug APK
./gradlew testCivDebugUnitTest      # unit tests
./gradlew assembleCivRelease        # signed release APK
```

Requires the ATAK 5.6.0 SDK. `local.properties` needs `sdk.dir`; the `atak-gradle-takdev`
plugin is resolved from the TAK repository or from `../../atak-gradle-takdev.jar`.

Native particle libraries are pre-built in `app/src/main/jniLibs/`. `externalNativeBuild`
is deliberately commented out — the release pipeline cannot install an NDK, so the `.so`
files ship as-is. To rebuild them, see `app/src/main/cpp/`.

## Reporting a problem

Open an [issue](https://github.com/Hellikandra/ATAK-Weather-Plugin/issues) with your
ATAK version, the plugin version from the panel, your device, and `adb logcat` output
if the plugin crashed or failed to load.

## Licence

See [LICENSE](LICENSE).
