# ATAK Weather Plugin — External Source Definitions

Place `.json` files in `/sdcard/atak/tools/weather_sources/` to add or override data sources
without recompiling the plugin.

## Quick start

1. Copy `TEMPLATE_weather_source.json` or `TEMPLATE_radar_source.json` to your device:
   ```
   /sdcard/atak/tools/weather_sources/my-source.json
   ```
2. Edit the file with your API details.
3. In ATAK → Weather Plugin → PARM tab, tap **⟳** (Refresh) to load the new definition.
4. Your source will appear in the Source spinner immediately.

---

## Weather source schema  (`sourceId` present → weather source)

| Field | Type | Required | Description |
|---|---|---|---|
| `sourceId` | string | ✅ | Unique ID, lowercase-hyphen (`my-source`). Matches `IWeatherRemoteSource.getSourceId()` if built-in. |
| `displayName` | string | ✅ | Human-readable name shown in the spinner. |
| `apiBaseUrl` | string | ✅ | Base URL of the weather API. |
| `requiresApiKey` | boolean | ✗ | If `true` the plugin will prompt for an API key (future). |
| `apiKey` | string | ✗ | Inline API key (use for personal/free-tier keys). |
| `description` | string | ✗ | Short description shown in the PARM tab. |
| `hourlyParams` | ParamEntry[] | ✗ | List of hourly parameters (see below). |
| `dailyParams` | ParamEntry[] | ✗ | List of daily parameters. |
| `currentParams` | ParamEntry[] | ✗ | List of current-conditions parameters. |

### ParamEntry fields

| Field | Type | Required | Description |
|---|---|---|---|
| `key` | string | ✅ | API parameter key (passed verbatim to the request). |
| `label` | string | ✅ | Display name in the PARM checklist. |
| `unit` | string | ✗ | Display unit suffix (e.g. `°C`, `m/s`, `%`). |
| `defaultOn` | boolean | ✗ | Pre-checked on first load (default: `false`). |

---

## Radar source schema  (`radarSourceId` present → radar source)

| Field | Type | Required | Description |
|---|---|---|---|
| `radarSourceId` | string | ✅ | Unique ID (`my-radar`). |
| `displayName` | string | ✅ | Shown in the CONF tab Source spinner. |
| `manifestUrl` | string | ✅ | URL returning a RainViewer-compatible JSON manifest (`radar.past[].time`). |
| `tileUrlTemplate` | string | ✅ | Tile URL with placeholders: `{timestamp}` `{z}` `{x}` `{y}` `{size}`. |
| `tileSize` | integer | ✗ | Tile size in pixels (default: 256). |
| `defaultZoom` | integer | ✗ | Default zoom level (default: 5). |
| `attribution` | string | ✗ | Attribution text. |

### Tile URL placeholders

| Placeholder | Replaced with |
|---|---|
| `{timestamp}` | Unix timestamp (seconds) from the manifest frame |
| `{z}` | Zoom level integer |
| `{x}` | Tile X index |
| `{y}` | Tile Y index |
| `{size}` | Tile size in px (from `tileSize` field, default `256`) |

---

## Override priority

Files are loaded in this order, later files win on duplicate IDs:

1. `assets/weather_sources/*.json` (bundled in the plugin APK)
2. `/sdcard/atak/tools/weather_sources/*.json` (user files, loaded alphabetically)

To override a built-in source, use the same `sourceId` / `radarSourceId` in your external file.

---

## YAML support (optional)

The plugin also accepts `.yaml` / `.yml` files in the same folder using the same field names.
The YAML file must be valid YAML 1.1 without aliases or anchors.
