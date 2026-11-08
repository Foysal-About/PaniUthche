# Flood-probability model (geospatial ML)

`risk = susceptibility x trigger`, per 1 km cell, for the next 7 days.

- **Susceptibility** (ML): a gradient-boosted classifier learns which terrain floods from Sentinel-1
  radar flood maps of past events. Features: height above nearest drainage, slope, land cover,
  upstream area, distance to permanent water.
- **Trigger**: the larger of GloFAS river flow at the area's main river (0 at the 2-year flow, 1 at the
  10-year flow, from 1994–2023 annual maxima; catches upstream rain) and NOAA GFS 3-day rain at the cell
  (0 at 88 mm, 1 at 264 mm: two BMD "heavy" to three "very heavy" days; catches local downpours).

Held-out result (train Sylhet 2022, test Feni 2024): ROC AUC 0.90, precision 0.89, recall 0.44 at 0.5.
Local rain and absolute elevation were removed as features after testing; see comments in `config.py`.

| Step | Script | Data |
|---|---|---|
| Labels + features | `build_dataset.py samples` | Sentinel-1, MERIT Hydro (HAND, drainage area), ESA WorldCover, JRC surface water |
| Train + held-out test | `train.py` | train on one flood, test on another (no spatial leakage) |
| Forecast grids | `build_dataset.py grid` | static features + exposure per cell: WorldPop 2025 people, ESA WorldCover cropland (ha), Google Open Buildings 2023 building count |
| Daily forecast | `forecast.py` | GloFAS river flow + NOAA GFS rain via Open-Meteo -> `out/risk_<area>.geojson`, `out/summary.json`; return levels cached in `data/river_ref.json` |
| Event replay | `replay.py` | Feni, 12–28 Aug 2024, observed GloFAS flow + ERA5 rain -> `replay/feni_2024/<date>.geojson`, `timeline.json` |

## Run

```sh
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/earthengine authenticate          # once; then set GEE_PROJECT in config.py
.venv/bin/python build_dataset.py samples   # ~minutes per event
.venv/bin/python train.py                   # prints held-out metrics, writes model/
.venv/bin/python build_dataset.py grid      # once per area
.venv/bin/python forecast.py                # daily
.venv/bin/python replay.py                  # once; outputs are committed and published
.venv/bin/python -m unittest test_pipeline  # offline checks
```

## Limits

- Sentinel-1 revisits every 6–12 days, so labels are a snapshot near the peak, not the full extent.
- Change-detection thresholds (`FLOOD_DROP_DB`, `FLOOD_WATER_DB`) need checking per event in the Earth Engine Code Editor.
- Two training events is a small sample; report the held-out metrics as a prototype result.
- Trigger scales come from return periods and BMD rain categories, not calibrated to flood outcomes.
- Built-up cells score low (~0.15 in Feni town): Sentinel-1 misses water between buildings (radar double-bounce), so
  urban flooding is under-labelled. In the replay the district reaches HIGH on 19 Aug 2024, but Feni town's own cell never passes 16%.
- The replay uses observed flow and rain, not archived forecasts: it shows where and when the model flags risk, not lead time.
- Training samples are 50/50 flooded/dry, so precision is optimistic versus real-world prevalence.
- Building counts are estimated from a 1-in-64 sample of Open Buildings' 0.5 m pixels; Kurigram and Sirajganj show ~2-3 people per building (many small rural structures), versus ~3.9 elsewhere.
- Livestock is not included: no gridded livestock dataset (e.g. FAO GLW) is hosted in Earth Engine.
- Not an official warning; FFWC and BMD remain the authority.
