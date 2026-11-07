# Flood-probability model (geospatial ML)

Learns where floods happen from Sentinel-1 radar flood maps of past events, then scores
1 km cells with 7-day GFS rain forecasts.

| Step | Script | Data |
|---|---|---|
| Labels + features | `build_dataset.py samples` | Sentinel-1, MERIT Hydro (HAND, elevation, drainage area), ESA WorldCover, JRC surface water, ERA5-Land rain |
| Train + held-out test | `train.py` | train on one flood, test on another (no spatial leakage) |
| Forecast grids | `build_dataset.py grid` | static features + WorldPop population per cell |
| Daily forecast | `forecast.py` | NOAA GFS rain via Open-Meteo -> `out/risk_<area>.geojson`, `out/summary.json` |

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
- Not an official warning; FFWC and BMD remain the authority.
