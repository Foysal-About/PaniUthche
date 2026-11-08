"""Replays the Aug 2024 Feni flood day by day through the forecast model and the app's alert rule.

  python replay.py   # replay/feni_2024/<date>.geojson + replay/feni_2024/timeline.json

Uses observed river flow (GloFAS reanalysis) and observed rain (ERA5), not archived forecasts:
it shows where and when the model flags risk as a flood develops, not forecast lead time.
"""
import json
import math
import os
from datetime import date, timedelta

import joblib
import pandas as pd

import config as C
import forecast as F

AREA = "feni"
START, END = date(2024, 8, 12), date(2024, 8, 28)
TOWN = (23.011, 91.397)  # Feni town (lat, lon): the "risk at your location" the app would show
OUT = os.path.join("replay", "feni_2024")
ARCHIVE = "https://archive-api.open-meteo.com/v1/archive"


def alert_level(prob: float) -> str:
    """Same thresholds as the app's alertLevel() in Geo.kt."""
    return "HIGH" if prob >= 0.5 else "MEDIUM" if prob >= 0.3 else "NONE"


def nearest_cell(grid: pd.DataFrame, lat: float, lon: float) -> int:
    d = (grid["latitude"] - lat) ** 2 + ((grid["longitude"] - lon) * math.cos(math.radians(lat))) ** 2
    return int(d.idxmin())


def day_row(day: date, scored: pd.DataFrame, town: int, q_over_q2: float, rain_3d: float) -> dict:
    exposed = scored[scored["prob"] >= F.EXPOSED_PROB]
    town_prob = float(scored.loc[town, "prob"])
    max_prob = float(scored["prob"].max())
    return {
        "date": str(day),
        "max_prob": round(max_prob, 3),
        # Without a location the app alerts on the district's max; with one, on the user's cell.
        "district_alert": alert_level(max_prob),
        "cells_at_risk": int(len(exposed)),
        "people_exposed": int(exposed["people"].sum()),
        "cropland_ha_exposed": int(exposed["cropland_ha"].sum()),
        "buildings_exposed": int(exposed["buildings"].sum()),
        # Built-up cells score low: Sentinel-1 labels miss water between buildings (radar double-bounce).
        "town_prob": round(town_prob, 3),
        "town_alert": alert_level(town_prob),
        "q_over_q2": round(q_over_q2, 2),
        "rain_3d_mm": round(rain_3d, 1),
    }


def main():
    bbox = C.AREAS[AREA]
    model = joblib.load(os.path.join(C.MODEL_DIR, "flood_model.joblib"))
    grid = pd.read_csv(os.path.join(C.DATA_DIR, f"grid_{AREA}.csv")).dropna(subset=C.FEATURES).reset_index(drop=True)
    susceptibility = model.predict_proba(grid[C.FEATURES].astype(float))[:, 1]

    ref = F.river_reference(AREA, bbox)
    flows = F.river_flows(ref, start_date=str(START), end_date=str(END))
    river = [F.river_trigger(q / ref["q2"], ref["q10"] / ref["q2"]) for q in flows]
    rain = F.fetch_rain(F.lattice(bbox, C.RAIN_LATTICE_DEG), url=ARCHIVE,
                        start_date=str(START - timedelta(days=F.PAST_DAYS)), end_date=str(END))
    probs = F.daily_risk(susceptibility, grid, bbox, river, rain)

    town = nearest_cell(grid, *TOWN)
    town_rain = F.rain_windows(rain[F.nearest_lattice(TOWN[1], TOWN[0], bbox, C.RAIN_LATTICE_DEG)])
    os.makedirs(OUT, exist_ok=True)
    timeline = []
    for i, p in enumerate(probs):
        day = START + timedelta(days=i)
        scored = grid.assign(susceptibility=susceptibility, prob=p, peak_day=0)
        with open(os.path.join(OUT, f"{day}.geojson"), "w") as f:
            json.dump(F.to_geojson(scored, day), f)
        timeline.append(day_row(day, scored, town, flows[i] / ref["q2"], town_rain[i][1]))
        print(timeline[-1])
    with open(os.path.join(OUT, "timeline.json"), "w") as f:
        json.dump({"area": AREA, "town": {"lat": TOWN[0], "lon": TOWN[1]}, "q2": ref["q2"], "q10": ref["q10"],
                   "source": "GloFAS reanalysis river flow + ERA5 rain (observed, not archived forecasts)",
                   "days": timeline}, f, indent=2)


if __name__ == "__main__":
    main()
