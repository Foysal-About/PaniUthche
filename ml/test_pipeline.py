"""Offline checks for the pure parts of the pipeline: python -m unittest test_pipeline"""
import unittest

import numpy as np
import pandas as pd

import config as C
from forecast import (cell_polygon, lattice, nearest_lattice, rain_trigger, rain_windows, return_levels, river_trigger,
                      susceptibility_geojson)
from train import evaluate, fit, split_by_event


class RainWindows(unittest.TestCase):
    def test_windows_end_on_each_forecast_day(self):
        daily = [1] * 7 + [10, 20, 30, 0, 0, 0, 5]
        w = rain_windows(daily)
        self.assertEqual(len(w), 7)
        self.assertEqual(w[0], (10, 1 + 1 + 10, 6 + 10))
        self.assertEqual(w[2], (30, 60, 4 + 60))

    def test_missing_values_count_as_zero(self):
        self.assertEqual(rain_windows([None] * 14)[0], (0.0, 0.0, 0.0))


class Triggers(unittest.TestCase):
    def test_river_trigger_scales_from_2yr_to_10yr_flow(self):
        q10 = 1.5  # 10-year flow is 1.5x the 2-year flow
        self.assertEqual(river_trigger(0.8, q10), 0.0)
        self.assertEqual(river_trigger(1.0, q10), 0.0)
        self.assertAlmostEqual(river_trigger(1.25, q10), 0.5)
        self.assertEqual(river_trigger(2.0, q10), 1.0)

    def test_rain_trigger_between_heavy_and_very_heavy_days(self):
        self.assertEqual(rain_trigger(50), 0.0)
        self.assertAlmostEqual(rain_trigger(176), 0.5)
        self.assertEqual(rain_trigger(400), 1.0)

    def test_return_levels_from_annual_maxima(self):
        q2, q10 = return_levels(list(range(1, 31)))
        self.assertAlmostEqual(q2, 15.5)
        self.assertAlmostEqual(q10, 27.1, places=1)


class Lattice(unittest.TestCase):
    bbox = [91.0, 24.5, 91.3, 24.7]

    def test_nearest_is_always_a_fetched_point(self):
        pts = set(lattice(self.bbox, 0.1))
        for lon, lat in [(91.04, 24.51), (91.29, 24.69), (90.9, 24.4), (91.5, 25.0)]:
            self.assertIn(nearest_lattice(lon, lat, self.bbox, 0.1), pts)

    def test_cell_polygon_is_closed_square_around_centre(self):
        ring = cell_polygon(91.0, 24.0, 1000)[0]
        self.assertEqual(ring[0], ring[-1])
        self.assertAlmostEqual((ring[2][1] - ring[0][1]) * 111_320, 1000, delta=5)


class TrainByEvent(unittest.TestCase):
    def synthetic(self, event, n=400, seed=0):
        rng = np.random.default_rng(seed)
        hnd = rng.uniform(0, 30, n)
        df = pd.DataFrame({
            "elv": hnd + rng.normal(0, 2, n), "hnd": hnd, "upa_log": rng.uniform(0, 4, n),
            "slope": rng.uniform(0, 5, n), "landcover": rng.choice([10, 40, 50, 80], n),
            "dist_water": rng.uniform(0, 20000, n),
        })
        # Cells close to river level flood.
        df["flood"] = (hnd < 8).astype(int)
        df["event"] = event
        return df

    def test_held_out_event_scores_well_on_learnable_signal(self):
        df = pd.concat([self.synthetic("a", seed=1), self.synthetic("b", seed=2)])
        train, test = split_by_event(df, "b")
        self.assertEqual(set(train["event"]), {"a"})
        metrics = evaluate(fit(train), test)
        self.assertGreater(metrics["roc_auc"], 0.9)

    def test_rejects_single_event(self):
        with self.assertRaises(SystemExit):
            split_by_event(self.synthetic("a"), "a")

    def test_feature_list_matches_categorical(self):
        self.assertTrue(set(C.CATEGORICAL) <= set(C.FEATURES))


class SusceptibilityLayer(unittest.TestCase):
    def test_draws_prone_cells_whatever_the_weather(self):
        scored = pd.DataFrame({"longitude": [91.0, 91.01], "latitude": [24.0, 24.0], "susceptibility": [0.05, 0.876],
                               "prob": [0.0, 0.0], "people": [5, 7]})
        feats = susceptibility_geojson(scored)["features"]
        self.assertEqual([f["properties"] for f in feats], [{"prob": 0.88, "people": 7}])


class ReplayDay(unittest.TestCase):
    def test_day_row_counts_exposed_cells_and_matches_app_alert_thresholds(self):
        from datetime import date
        from replay import alert_level, day_row, nearest_cell
        self.assertEqual([alert_level(p) for p in (0.29, 0.3, 0.49, 0.5)], ["NONE", "MEDIUM", "MEDIUM", "HIGH"])
        scored = pd.DataFrame({"latitude": [23.0, 23.01, 23.02], "longitude": [91.4] * 3, "prob": [0.2, 0.6, 0.35],
                               "people": [10, 20, 30], "cropland_ha": [1.0, 2.0, 3.0], "buildings": [1, 2, 3]})
        town = nearest_cell(scored, 23.019, 91.4)
        row = day_row(date(2024, 8, 19), scored, town, 1.04, 198.4)
        self.assertEqual(town, 2)
        self.assertEqual((row["cells_at_risk"], row["people_exposed"]), (1, 20))
        self.assertEqual((row["district_alert"], row["town_alert"]), ("HIGH", "MEDIUM"))


if __name__ == "__main__":
    unittest.main()
