# Flood-probability model (geospatial ML)

`risk = susceptibility x trigger`, per 1 km cell, for the next 7 days.

- **Susceptibility** (ML): a gradient-boosted classifier learns which terrain floods from Sentinel-1
  radar flood maps of past events. Features: height above nearest drainage, slope, land cover,
  upstream area, distance to permanent water.
- **Trigger**: the larger of GloFAS river flow vs its 90-day normal (catches upstream rain) and
  NOAA GFS 3-day rain at the cell (catches local downpours).

Held-out result (train Sylhet 2022, test Feni 2024): ROC AUC 0.90, precision 0.89, recall 0.44 at 0.5.
Local rain and absolute elevation were removed as features after testing; see comments in `config.py`.

| Step | Script | Data |
|---|---|---|
| Labels + features | `build_dataset.py samples` | Sentinel-1, MERIT Hydro (HAND, drainage area), ESA WorldCover, JRC surface water |
| Train + held-out test | `train.py` | train on one flood, test on another (no spatial leakage) |
| Forecast grids | `build_dataset.py grid` | static features + WorldPop population per cell |
| Daily forecast | `forecast.py` | GloFAS river flow + NOAA GFS rain via Open-Meteo -> `out/risk_<area>.geojson`, `out/summary.json` |

## Run

```sh
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/earthengine authenticate          # once; then set GEE_PROJECT in config.py
.venv/bin/python build_dataset.py samples   # ~minutes per event
.venv/bin/python train.py                   # prints held-out metrics, writes model/
.venv/bin/python build_dataset.py grid      # once per area
.venv/bin/python forecast.py                # daily
.venv/bin/python -m unittest test_pipeline  # offline checks
```

## Limits

- Sentinel-1 revisits every 6–12 days, so labels are a snapshot near the peak, not the full extent.
- Change-detection thresholds (`FLOOD_DROP_DB`, `FLOOD_WATER_DB`) need checking per event in the Earth Engine Code Editor.
- Two training events is a small sample; report the held-out metrics as a prototype result.
- Trigger scales (2x normal flow, 150 mm in 3 days) are hand-set, not calibrated.
- Training samples are 50/50 flooded/dry, so precision is optimistic versus real-world prevalence.
- Not an official warning; FFWC and BMD remain the authority.
