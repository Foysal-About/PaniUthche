# PaniUthche — session handoff (2026-11-08)

Read this first in a new session. It records the competition context, what is built, the work that was
in progress when the session was cleared, and the gotchas that cost time.

## Competition

- **Event:** International AI Builders Congress 2026, CloudCamp Bangladesh (cloudcampbd.com/dashboard).
- **Track:** Idea to Unicorn. **Domain:** ClimateSphere AI. **Challenge:** Climate Shield
  ("Climate risk prediction and disaster early warning"). Specific ask: *lives and assets protected*.
  Details: *forecasting + geospatial ML + alerts*. The ViT/GNN/RL/LSTM prompts are only examples.
- **Preliminary deadline:** **Nov 20, 2026, 23:59 Bangladesh time** (extended from Oct 31). Final: Dec 24. Congress Day: Dec 26.
- **Team rule:** 3–6 members; **solo entries are ineligible** for Idea to Unicorn. The user is building alone and
  still needs 2+ real registered teammates. Biggest open risk.
- **Rubric:** Innovation 20, Technical 20, Business model 20, Real-world impact + ethics 20, Scalability + NRB 10, Presentation 10.
- Official challenge list and FAQ snapshot: github.com/abumusasakib/cloudcamp-bd-mcp (`knowledge-base-ai-builders-congress.md`).

## What the product is

**Pani Uthche** ("the water is rising"): an Android flood early-warning app for Bangladesh, plus an ML pipeline.

- Repo: `~/StudioProjects/PaniUthche`, GitHub `Foysal-About/PaniUthche` (public), branch `main`.
- Live ML output (GitHub Pages, rebuilt 06:30 and 18:30 BST by `.github/workflows/forecast.yml`):
  `https://foysal-about.github.io/PaniUthche/summary.json`, `risk_<area>.geojson`, `metrics.json`.

### Android app (`app/`, Kotlin + Jetpack Compose)

| File | What it does |
|---|---|
| `MainActivity.kt` | App shell (3 tabs), `ForecastViewModel`, Forecast screen with one ML-driven headline risk card, AI stats, river-flow section |
| `MapScreen.kt` | Flood map tab: osmdroid map, 1 km risk cells, "you are here" marker |
| `SosScreen.kt`, `SosMesh.kt`, `SosMessage.kt` | Offline SOS mesh over Nearby Connections (store-and-forward, 5 hops) |
| `Alerts.kt`, `AlertsCard.kt`, `Geo.kt` | GPS (LocationManager), nearest district, risk at the user's cell, WorkManager 6-hourly alerts, notifications |
| `MlForecast.kt` | Downloads + caches the published ML JSON for offline use |
| `Forecast.kt` | GloFAS river flow per district (Open-Meteo), the older river-only rule (now fallback only) |
| `Theme.kt` | Colours, dark mode, in-app Bangla/English switch (`LocalizedContent`), Bangla digits |

Strings live in `res/values/strings.xml` (English) and `res/values-bn/strings.xml` (Bangla). Default language is Bangla.
Unit tests: `RiskTest`, `RelayTest`, `GeoTest` (15 tests).

### ML pipeline (`ml/`, Python venv at `ml/.venv`)

- `risk = susceptibility(cell) x trigger(cell, day)`, 1 km cells, 5 pilot districts (Sylhet, Sunamganj, Kurigram, Feni, Sirajganj).
- **Susceptibility:** scikit-learn `HistGradientBoostingClassifier` on terrain only (HAND, slope, land cover,
  upstream area, distance to water), labels from Sentinel-1 flood maps of Sylhet 2022 and Feni 2024.
  Held-out (train Sylhet, test Feni): AUC 0.898, precision 0.888, recall 0.44 at 0.5.
  Local rain and absolute elevation were dropped as features after testing (reasons in `config.py`).
- **Exposure per cell:** WorldPop 2025 people, ESA WorldCover cropland (ha), Google Open Buildings 2023 building count.
- Files: `config.py`, `build_dataset.py` (Earth Engine export), `train.py`, `forecast.py`, `test_pipeline.py`, `README.md`.
- Earth Engine project: `idyllic-creek-446208-a1` (non-commercial, Community tier, registered as BRAC University student).

## Done: Feni Aug 2024 replay

- Triggers use return periods (river 0 at Q2, 1 at Q10; rain 0 at 88 mm/3 days, 1 at 264 mm). `ml/data/river_ref.json` has all 5 areas.
- `ml/replay.py` -> `ml/replay/feni_2024/<date>.geojson` + `timeline.json` (Aug 12–28 2024, GloFAS reanalysis + ERA5).
  Published on Pages: `forecast.py` copies `replay/` into `out/` (the workflow file is unchanged).
- App: Map tab has a "Replay: Feni flood, Aug 2024" chip with day slider, play, day stats, and "show alert as on this day".
- Result: district alert MEDIUM Aug 18, HIGH Aug 19 (river crossed Q2) to Aug 28; up to 1.38 M people in cells >= 50%.
- **Model flaw found:** built-up cells score ~0.15 (cropland 0.8–0.9), so Feni town's own cell never passes 16% and a
  located user in town gets no alert. Likely cause: Sentinel-1 misses water between buildings, so urban flooding is
  under-labelled. Documented in `ml/README.md`; not fixed.

**Be honest in the write-up:** the replay uses *observed* river flow and rain (GloFAS reanalysis, ERA5), not archived
forecasts. It shows where and when the model flags risk as a flood develops. It does not measure forecast lead time.

## Done: GPS in SOS messages

`SosMessage` carries optional `lat`/`lon`. GPS warms up when the mesh starts; on send it waits up to 10 s, else sends
without location. Received cards show coordinates + distance; tap opens a map app. Older messages without location still parse.

## Still missing

- **Team of 3+ people** (blocking).
- Urban under-prediction (above).
- SOS: real-phone test (two devices), save/upload messages, fix age in messages; recheck SOS tab in dark mode and Bangla.
- Livestock exposure (no gridded livestock dataset in Earth Engine).
- Release APK + download link (GitHub Releases).
- ML: eyeball Sentinel-1 labels; recall is low at 0.5.
- Submission: portal text (problem, solution, target user, team roles, mentor notes), 2–3 min YouTube demo,
  main README with architecture diagram, disclosures (prompt library, MCP, tools/IDE, data/AI provenance),
  business model + impact numbers + pitch deck, optional MCP server.

## Commands

```sh
# Android build + tests (JDK from Android Studio)
cd ~/StudioProjects/PaniUthche && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest assembleDebug -q
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# Emulator (AVD Pixel_10_Pro_XL); put GPS in Feni town
~/Library/Android/sdk/emulator/emulator -avd Pixel_10_Pro_XL -no-snapshot-save -no-boot-anim &
~/Library/Android/sdk/platform-tools/adb emu geo fix 91.397 23.011

# ML
cd ~/StudioProjects/PaniUthche/ml
.venv/bin/python -m unittest test_pipeline
.venv/bin/python build_dataset.py samples   # Earth Engine; ~30 s
.venv/bin/python train.py
.venv/bin/python build_dataset.py grid      # Earth Engine; ~10 min (0.1 degree tiles)
.venv/bin/python forecast.py
.venv/bin/python replay.py                  # Feni 2024 replay; commit its outputs
```

## Gotchas that cost time

- **Emulator** often shuts down or sleeps mid-test. Check `adb get-state` before each step; `adb shell svc power stayon true`.
  Emulator Bluetooth crashes on its own (`com.google.android.bluetooth`); that is not the app.
- **Language switch:** `LocalizedContent` swaps `LocalContext`, which broke every permission launcher until the
  Activity's `LocalActivityResultRegistryOwner` was passed through explicitly. Keep that in `Theme.kt`.
- Prefs that must survive a quick kill (alerts toggle, language, location, last alert) use `commit()`, not `apply()`.
- Kotlin is 2.2.10 (AGP 9.4.1): **Nearby is pinned to 19.3.0** (19.5.1 needs Kotlin 2.4 metadata); WorkManager 2.11.2.
- Android 17 (API 37) needs `ACCESS_LOCAL_NETWORK` for Nearby advertising.
- LightGBM needs `libomp` (not installed), so the model uses scikit-learn. CI pins `scikit-learn==1.9.1` to load the joblib model.
- GloFAS cells are ~5 km; a town's own cell is often a tiny stream. Always pick the main-river cell (largest typical flow).
- Open Buildings native pixels are 0.5 m: sampled at 4 m and scaled x64; 0.3 degree tiles exceed EE reprojection limits, so tiles are 0.1 degree.
- WorldPop `WorldPop/GP/100m/pop` is deprecated; use `WorldPop/GP/100m/R2025A/v1/pop`.
- Open-Meteo free tier has an **hourly** request limit; 30-year multi-point requests use it up fast.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commit/push only when the user asks.
