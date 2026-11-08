"""Scores each area's grid and writes map layers for the app.

  python forecast.py   # out/risk_<area>.geojson + out/summary.json

risk(cell, day) = susceptibility(cell) x trigger(cell, day)
  susceptibility: the terrain model trained on Sentinel-1 flood maps (train.py)
  trigger: the larger of
    - river: GloFAS discharge at the area's main river vs its 2- and 10-year return levels
             (catches upstream rain; references cached in data/river_ref.json)
    - rain:  NOAA GFS 3-day rain at the cell (catches local downpours), via Open-Meteo on a coarse lattice
"""
import json
import math
import os
import shutil
from datetime import date, timedelta

import joblib
import numpy as np
import pandas as pd
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import config as C

# Open-Meteo occasionally times out; retry with backoff (5, 10, 20, 40 s) before failing the run.
http = requests.Session()
http.mount("https://", HTTPAdapter(max_retries=Retry(
    total=4, backoff_factor=5, status_forcelist=[429, 500, 502, 503, 504], allowed_methods=["GET"])))

PAST_DAYS = 7
FORECAST_DAYS = 7
MIN_PROB_TO_DRAW = 0.1   # keeps the GeoJSON small for the phone
EXPOSED_PROB = 0.5       # a cell's people count as exposed at or above this
# Trigger scales come from standard references, not fitted to any event:
#   river: 0 at the 2-year return flow (~bankfull), 1 at the 10-year return flow (GloFAS 1994-2023 annual maxima)
#   rain:  0 at 88 mm in 3 days (two BMD "heavy rain" days), 1 at 264 mm (three BMD "very heavy" days, 89+ mm)
# A 90-day-median baseline was dropped: by mid-monsoon flow sits far above it, so it alarmed for weeks.
RAIN_ZERO_3D_MM = 88.0
RAIN_FULL_3D_MM = 264.0
REF_YEARS = ("1994-01-01", "2023-12-31")
REF_PATH = os.path.join(C.DATA_DIR, "river_ref.json")
FLOOD_API = "https://flood-api.open-meteo.com/v1/flood"


def river_trigger(q_over_q2: float, q10_over_q2: float) -> float:
    """0 at or below the 2-year flow, 1 at the 10-year flow."""
    return min(max((q_over_q2 - 1) / max(q10_over_q2 - 1, 1e-6), 0.0), 1.0)


def rain_trigger(rain_3d_mm: float) -> float:
    return min(max((rain_3d_mm - RAIN_ZERO_3D_MM) / (RAIN_FULL_3D_MM - RAIN_ZERO_3D_MM), 0.0), 1.0)


def return_levels(annual_maxima: list) -> tuple:
    """(Q2, Q10) as empirical quantiles of annual maxima (2-year = median, 10-year = 90th percentile)."""
    am = np.asarray(annual_maxima, dtype=float)
    return float(np.median(am)), float(np.quantile(am, 0.9))


def build_river_reference(bbox) -> dict:
    """Pick the area's main-river GloFAS cell and its Q2/Q10 from 30 years of daily flow.

    GloFAS cells are ~5 km and a point often lands on a small stream, so search a 5x5 lattice
    across the area and keep the cell with the largest typical flow.
    """
    w, s, e, n = bbox
    pts = [(w + (e - w) * i / 4, s + (n - s) * j / 4) for i in range(5) for j in range(5)]
    # One year is enough to find the main river; then pull 30 years for that cell only (Open-Meteo rate limits).
    r = http.get(FLOOD_API, timeout=120, params={
        "latitude": ",".join(f"{la:.3f}" for _, la in pts),
        "longitude": ",".join(f"{lo:.3f}" for lo, _ in pts),
        "daily": "river_discharge", "start_date": "2023-01-01", "end_date": "2023-12-31",
    })
    r.raise_for_status()
    best = max(r.json(), key=lambda loc: float(np.median([x or 0.0 for x in loc["daily"]["river_discharge"]])))
    r = http.get(FLOOD_API, timeout=300, params={
        "latitude": best["latitude"], "longitude": best["longitude"], "daily": "river_discharge",
        "start_date": REF_YEARS[0], "end_date": REF_YEARS[1],
    })
    r.raise_for_status()
    daily = r.json()["daily"]
    maxima = {}
    for day, q in zip(daily["time"], daily["river_discharge"]):
        maxima[day[:4]] = max(maxima.get(day[:4], 0.0), q or 0.0)
    q2, q10 = return_levels(list(maxima.values()))
    return {"lat": best["latitude"], "lon": best["longitude"], "q2": round(q2, 1), "q10": round(q10, 1),
            "years": len(maxima)}


def river_reference(name: str, bbox) -> dict:
    refs = json.load(open(REF_PATH)) if os.path.exists(REF_PATH) else {}
    if name not in refs:
        refs[name] = build_river_reference(bbox)
        with open(REF_PATH, "w") as f:
            json.dump(refs, f, indent=2)
    return refs[name]


def river_flows(ref: dict, **window) -> list:
    """Daily GloFAS flow at the reference cell; window is forecast_days or start_date/end_date."""
    r = http.get(FLOOD_API, timeout=60, params={
        "latitude": ref["lat"], "longitude": ref["lon"], "daily": "river_discharge", **window})
    r.raise_for_status()
    return [q or 0.0 for q in r.json()["daily"]["river_discharge"]]


def river_triggers(ref: dict, **window) -> list:
    return [river_trigger(q / ref["q2"], ref["q10"] / ref["q2"]) for q in river_flows(ref, **window)]


def rain_windows(daily: list) -> list:
    """Per forecast day: (rain_1d, rain_3d, rain_7d) in mm, each window ending on that day.

    `daily` holds PAST_DAYS observed days then FORECAST_DAYS forecast days (today first).
    """
    p = [x or 0.0 for x in daily]
    return [(p[i], sum(p[i - 2:i + 1]), sum(p[i - 6:i + 1])) for i in range(PAST_DAYS, len(p))]


def lattice(bbox, step: float) -> list:
    w, s, e, n = bbox
    lons = np.arange(w, e + step / 2, step)
    lats = np.arange(s, n + step / 2, step)
    return [(round(lo, 4), round(la, 4)) for la in lats for lo in lons]


def nearest_lattice(lon: float, lat: float, bbox, step: float) -> tuple:
    w, s, e, n = bbox
    lo = min(max(round((lon - w) / step) * step + w, w), w + math.floor((e - w) / step + 0.5) * step)
    la = min(max(round((lat - s) / step) * step + s, s), s + math.floor((n - s) / step + 0.5) * step)
    return round(lo, 4), round(la, 4)


def cell_polygon(lon: float, lat: float, scale_m: float) -> list:
    dlat = scale_m / 2 / 111_320
    dlon = dlat / math.cos(math.radians(lat))
    ring = [(lon - dlon, lat - dlat), (lon + dlon, lat - dlat), (lon + dlon, lat + dlat),
            (lon - dlon, lat + dlat), (lon - dlon, lat - dlat)]
    return [[[round(x, 5), round(y, 5)] for x, y in ring]]


GFS_URL = "https://api.open-meteo.com/v1/forecast"
GFS_WINDOW = {"models": "gfs_seamless", "past_days": PAST_DAYS, "forecast_days": FORECAST_DAYS}


def fetch_rain(points: list, url: str = GFS_URL, **window) -> dict:
    """{(lon, lat): [daily precipitation mm]}: PAST_DAYS lead-in days, then one value per scored day."""
    out = {}
    for i in range(0, len(points), 100):
        chunk = points[i:i + 100]
        r = http.get(url, timeout=60, params={
            "latitude": ",".join(str(la) for _, la in chunk),
            "longitude": ",".join(str(lo) for lo, _ in chunk),
            "daily": "precipitation_sum", "timezone": "Asia/Dhaka", **(window or GFS_WINDOW),
        })
        r.raise_for_status()
        body = r.json()
        for pt, loc in zip(chunk, body if isinstance(body, list) else [body]):
            out[pt] = loc["daily"]["precipitation_sum"]
    return out


def daily_risk(susceptibility, grid: pd.DataFrame, bbox, river: list, rain: dict) -> np.ndarray:
    """(days x cells) risk = susceptibility x max(river trigger, rain trigger at the cell's lattice point)."""
    windows = {pt: rain_windows(daily) for pt, daily in rain.items()}
    keys = [nearest_lattice(lo, la, bbox, C.RAIN_LATTICE_DEG) for lo, la in zip(grid["longitude"], grid["latitude"])]
    return np.vstack([
        susceptibility * np.maximum(river[day], [rain_trigger(windows[k][day][1]) for k in keys])
        for day in range(len(river))
    ])


def score_area(model, grid: pd.DataFrame, name: str, bbox) -> pd.DataFrame:
    susceptibility = model.predict_proba(grid[C.FEATURES].astype(float))[:, 1]
    river = river_triggers(river_reference(name, bbox), forecast_days=FORECAST_DAYS)
    probs = daily_risk(susceptibility, grid, bbox, river, fetch_rain(lattice(bbox, C.RAIN_LATTICE_DEG)))
    return grid.assign(susceptibility=susceptibility, prob=probs.max(axis=0), peak_day=probs.argmax(axis=0))


def to_geojson(scored: pd.DataFrame, first_day: date | None = None) -> dict:
    today = first_day or date.today()
    feats = [{
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": cell_polygon(r.longitude, r.latitude, C.GRID_SCALE_M)},
        "properties": {"prob": round(float(r.prob), 3), "susceptibility": round(float(r.susceptibility), 3),
                       "peak_date": str(today + timedelta(days=int(r.peak_day))), "people": int(r.people),
                       "cropland_ha": round(float(r.cropland_ha), 1), "buildings": int(r.buildings)},
    } for r in scored.itertuples() if r.prob >= MIN_PROB_TO_DRAW]
    return {"type": "FeatureCollection", "features": feats}


def main():
    model = joblib.load(os.path.join(C.MODEL_DIR, "flood_model.joblib"))
    os.makedirs(C.OUT_DIR, exist_ok=True)
    summary = {"generated": str(date.today()), "areas": {}}
    for name, bbox in C.AREAS.items():
        path = os.path.join(C.DATA_DIR, f"grid_{name}.csv")
        if not os.path.exists(path):
            print(f"skip {name}: {path} missing (run `python build_dataset.py grid`)")
            continue
        grid = pd.read_csv(path).dropna(subset=C.FEATURES)
        scored = score_area(model, grid, name, bbox)
        with open(os.path.join(C.OUT_DIR, f"risk_{name}.geojson"), "w") as f:
            json.dump(to_geojson(scored), f)
        exposed = scored[scored["prob"] >= EXPOSED_PROB]
        summary["areas"][name] = {
            "max_prob": round(float(scored["prob"].max()), 3),
            "cells_at_risk": int(len(exposed)),
            "people_exposed": int(exposed["people"].sum()),
            "cropland_ha_exposed": int(exposed["cropland_ha"].sum()),
            "buildings_exposed": int(exposed["buildings"].sum()),
        }
        print(name, summary["areas"][name])
    with open(os.path.join(C.OUT_DIR, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    # Publish the committed flood replay (replay.py) with the forecast; the workflow uploads out/ as-is.
    if os.path.isdir("replay"):
        shutil.copytree("replay", os.path.join(C.OUT_DIR, "replay"), dirs_exist_ok=True)


if __name__ == "__main__":
    main()
