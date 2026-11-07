"""Offline checks for the pure parts of the pipeline: python -m unittest test_pipeline"""
import unittest

import numpy as np
import pandas as pd

import config as C
from forecast import cell_polygon, lattice, nearest_lattice, rain_windows
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
        rain = rng.uniform(0, 300, n)
        df = pd.DataFrame({
            "elv": hnd + rng.normal(0, 2, n), "hnd": hnd, "upa_log": rng.uniform(0, 4, n),
            "slope": rng.uniform(0, 5, n), "landcover": rng.choice([10, 40, 50, 80], n),
            "dist_water": rng.uniform(0, 20000, n), "rain_1d": rain / 5, "rain_3d": rain / 2, "rain_7d": rain,
        })
        # Low-lying cells after heavy rain flood.
        df["flood"] = ((hnd < 8) & (rain > 120)).astype(int)
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


if __name__ == "__main__":
    unittest.main()
