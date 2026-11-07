"""Scores each area's grid with forecast rain and writes map layers for the app.

  python forecast.py   # out/risk_<area>.geojson + out/summary.json

Rain comes from NOAA GFS through the Open-Meteo forecast API (7 past days + 7 forecast days),
fetched on a coarse lattice and assigned to the nearest lattice point for each cell.
"""
import json
import math
import os
from datetime import date, timedelta

import joblib
import numpy as np
import pandas as pd
import requests

import config as C

PAST_DAYS = 7
FORECAST_DAYS = 7
MIN_PROB_TO_DRAW = 0.1   # keeps the GeoJSON small for the phone
EXPOSED_PROB = 0.5       # a cell's people count as exposed at or above this


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


def fetch_rain(points: list) -> dict:
    """{(lon, lat): [daily precipitation mm, PAST_DAYS + FORECAST_DAYS values]} from GFS."""
    out = {}
    for i in range(0, len(points), 100):
        chunk = points[i:i + 100]
        r = requests.get("https://api.open-meteo.com/v1/forecast", timeout=60, params={
            "latitude": ",".join(str(la) for _, la in chunk),
            "longitude": ",".join(str(lo) for lo, _ in chunk),
            "daily": "precipitation_sum", "models": "gfs_seamless",
            "past_days": PAST_DAYS, "forecast_days": FORECAST_DAYS, "timezone": "Asia/Dhaka",
        })
        r.raise_for_status()
        body = r.json()
        for pt, loc in zip(chunk, body if isinstance(body, list) else [body]):
            out[pt] = loc["daily"]["precipitation_sum"]
    return out


def score_area(model, grid: pd.DataFrame, bbox) -> pd.DataFrame:
    pts = lattice(bbox, C.RAIN_LATTICE_DEG)
    windows = {pt: rain_windows(daily) for pt, daily in fetch_rain(pts).items()}
    keys = [nearest_lattice(lo, la, bbox, C.RAIN_LATTICE_DEG) for lo, la in zip(grid["longitude"], grid["latitude"])]
    probs = []
    for day in range(FORECAST_DAYS):
        X = grid[C.FEATURES[:6]].copy()
        X[["rain_1d", "rain_3d", "rain_7d"]] = np.array([windows[k][day] for k in keys])
        probs.append(model.predict_proba(X[C.FEATURES].astype(float))[:, 1])
    probs = np.vstack(probs)
    return grid.assign(prob=probs.max(axis=0), peak_day=probs.argmax(axis=0))


def to_geojson(scored: pd.DataFrame) -> dict:
    today = date.today()
    feats = [{
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": cell_polygon(r.longitude, r.latitude, C.GRID_SCALE_M)},
        "properties": {"prob": round(float(r.prob), 3), "peak_date": str(today + timedelta(days=int(r.peak_day))),
                       "people": int(r.people)},
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
        grid = pd.read_csv(path).dropna(subset=C.FEATURES[:6])
        scored = score_area(model, grid, bbox)
        with open(os.path.join(C.OUT_DIR, f"risk_{name}.geojson"), "w") as f:
            json.dump(to_geojson(scored), f)
        exposed = scored[scored["prob"] >= EXPOSED_PROB]
        summary["areas"][name] = {
            "max_prob": round(float(scored["prob"].max()), 3),
            "cells_at_risk": int(len(exposed)),
            "people_exposed": int(exposed["people"].sum()),
        }
        print(name, summary["areas"][name])
    with open(os.path.join(C.OUT_DIR, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)


if __name__ == "__main__":
    main()
