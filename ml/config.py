"""Shared settings for the flood-probability pipeline."""

# Earth Engine Cloud project registered for non-commercial use.
GEE_PROJECT = "idyllic-creek-446208-a1"

# Past floods with Sentinel-1 coverage, used as training labels.
# before: dry-season reference window. during: imagery window at/after the flood peak.
# Rain features are summed over the days just before `during[0]`.
EVENTS = {
    "sylhet_2022": {
        "bbox": [91.0, 24.5, 92.2, 25.3],
        "before": ("2022-02-01", "2022-03-31"),
        "during": ("2022-06-17", "2022-06-25"),
    },
    "feni_2024": {
        "bbox": [91.2, 22.8, 91.7, 23.3],
        "before": ("2024-02-01", "2024-03-31"),
        "during": ("2024-08-22", "2024-08-30"),
    },
}

# Areas the daily forecast covers (match the app's pilot districts).
AREAS = {
    "sylhet": [91.0, 24.5, 92.2, 25.3],
    "sunamganj": [90.9, 24.8, 91.7, 25.3],
    "kurigram": [89.4, 25.5, 89.95, 26.1],
    "feni": [91.2, 22.8, 91.7, 23.3],
    "sirajganj": [89.5, 24.2, 89.9, 24.7],
}

# Sentinel-1 VH change detection (dB). Tune per event by eye in the Code Editor.
FLOOD_DROP_DB = -3.0      # during minus before
FLOOD_WATER_DB = -18.0    # during must also look like open water

SAMPLE_SCALE_M = 100      # training sample resolution
SAMPLES_PER_CLASS = 3000  # per event, flooded and dry each
GRID_SCALE_M = 1000       # forecast cell size
RAIN_LATTICE_DEG = 0.1    # forecast rain fetched on this lattice, then assigned to cells

FEATURES = ["elv", "hnd", "upa_log", "slope", "landcover", "dist_water", "rain_1d", "rain_3d", "rain_7d"]
CATEGORICAL = ["landcover"]

DATA_DIR = "data"
MODEL_DIR = "model"
OUT_DIR = "out"
