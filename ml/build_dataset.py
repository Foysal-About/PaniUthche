"""Exports training samples and forecast grids from Earth Engine.

  python build_dataset.py samples   # data/samples_<event>.csv  (features + flood label)
  python build_dataset.py grid      # data/grid_<area>.csv      (static features + population per cell)
"""
import os
import sys

import ee
import pandas as pd

import config as C


def static_features() -> ee.Image:
    merit = ee.Image("MERIT/Hydro/v1_0_1")
    elv = merit.select("elv")
    # Permanent water (water >= 80% of the time) and distance to it in metres.
    water = ee.Image("JRC/GSW1_4/GlobalSurfaceWater").select("occurrence").unmask(0).gte(80)
    dist = ee.Image(1).cumulativeCost(source=water.selfMask(), maxDistance=20000).unmask(20000)
    return ee.Image.cat(
        elv.rename("elv"),
        merit.select("hnd").rename("hnd"),
        merit.select("upa").add(1).log10().rename("upa_log"),  # upstream drainage area, km²
        ee.Terrain.slope(elv).rename("slope"),
        ee.ImageCollection("ESA/WorldCover/v200").first().select("Map").rename("landcover"),
        dist.rename("dist_water"),
    )


def rain_features(end_date: str) -> ee.Image:
    """Rain (mm) over 1/3/7 days ending the day before end_date, from ERA5-Land."""
    end = ee.Date(end_date)
    era5 = ee.ImageCollection("ECMWF/ERA5_LAND/DAILY_AGGR").select("total_precipitation_sum")
    def total(days):
        return era5.filterDate(end.advance(-days, "day"), end).sum().multiply(1000)
    return ee.Image.cat(total(1).rename("rain_1d"), total(3).rename("rain_3d"), total(7).rename("rain_7d"))


def flood_label(event: dict, aoi: ee.Geometry) -> ee.Image:
    s1 = (ee.ImageCollection("COPERNICUS/S1_GRD").filterBounds(aoi)
          .filter(ee.Filter.eq("instrumentMode", "IW"))
          .filter(ee.Filter.listContains("transmitterReceiverPolarisation", "VH"))
          .select("VH"))
    before = s1.filterDate(*event["before"]).median().focalMean(50, "circle", "meters")
    during = s1.filterDate(*event["during"]).min().focalMean(50, "circle", "meters")
    flooded = during.subtract(before).lt(C.FLOOD_DROP_DB).And(during.lt(C.FLOOD_WATER_DB))
    # Exclude rivers and ponds that are water most of the year: those are not floods.
    seasonal = ee.Image("JRC/GSW1_4/GlobalSurfaceWater").select("seasonality").unmask(0)
    return flooded.updateMask(seasonal.lt(10)).rename("flood").toInt()


def to_frame(fc: ee.FeatureCollection) -> pd.DataFrame:
    return ee.data.computeFeatures({"expression": fc, "fileFormat": "PANDAS_DATAFRAME"})


def export_samples():
    for name, ev in C.EVENTS.items():
        aoi = ee.Geometry.Rectangle(ev["bbox"])
        img = ee.Image.cat(static_features(), rain_features(ev["during"][0]),
                           flood_label(ev, aoi), ee.Image.pixelLonLat())
        fc = img.stratifiedSample(
            numPoints=C.SAMPLES_PER_CLASS, classBand="flood", region=aoi, scale=C.SAMPLE_SCALE_M,
            classValues=[0, 1], classPoints=[C.SAMPLES_PER_CLASS] * 2, seed=42, tileScale=4,
        )
        df = to_frame(fc).drop(columns=["geo"], errors="ignore")
        df["event"] = name
        path = os.path.join(C.DATA_DIR, f"samples_{name}.csv")
        df.to_csv(path, index=False)
        print(f"{name}: {len(df)} samples, {int(df['flood'].sum())} flooded -> {path}")


def export_grids():
    pop = (ee.ImageCollection("WorldPop/GP/100m/pop").filter(ee.Filter.eq("country", "BGD"))
           .filter(ee.Filter.eq("year", 2020)).first().select("population"))
    # People per km², averaged per cell, then scaled to the cell area.
    density = pop.divide(ee.Image.pixelArea()).multiply(1e6).rename("pop_density")
    img = ee.Image.cat(static_features(), density, ee.Image.pixelLonLat())
    for name, (w, s, e, n) in C.AREAS.items():
        # Split into ~0.3° tiles so each request stays under Earth Engine's size limits.
        frames, step = [], 0.3
        lon = w
        while lon < e:
            lat = s
            while lat < n:
                tile = ee.Geometry.Rectangle([lon, lat, min(lon + step, e), min(lat + step, n)])
                frames.append(to_frame(img.sample(region=tile, scale=C.GRID_SCALE_M, tileScale=4)))
                lat += step
            lon += step
        df = pd.concat(frames, ignore_index=True).drop(columns=["geo"], errors="ignore")
        df["people"] = df.pop("pop_density").fillna(0) * (C.GRID_SCALE_M / 1000) ** 2
        path = os.path.join(C.DATA_DIR, f"grid_{name}.csv")
        df.to_csv(path, index=False)
        print(f"{name}: {len(df)} cells, {df['people'].sum():,.0f} people -> {path}")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("samples", "grid"):
        sys.exit(__doc__)
    ee.Initialize(project=C.GEE_PROJECT)
    os.makedirs(C.DATA_DIR, exist_ok=True)
    export_samples() if sys.argv[1] == "samples" else export_grids()
