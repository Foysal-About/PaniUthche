"""Trains the flood classifier on some events and tests it on a held-out event.

  python train.py [--test-event feni_2024]

Splitting by event matters: neighbouring cells look nearly identical, so a random split
would leak and overstate accuracy.
"""
import argparse
import glob
import json
import os

import joblib
import matplotlib
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.inspection import permutation_importance
from sklearn.metrics import average_precision_score, f1_score, precision_score, recall_score, roc_auc_score

import config as C

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402


def load_samples(paths) -> pd.DataFrame:
    df = pd.concat([pd.read_csv(p) for p in paths], ignore_index=True)
    return df.dropna(subset=C.FEATURES + ["flood"])


def split_by_event(df: pd.DataFrame, test_event: str):
    if test_event not in set(df["event"]):
        raise SystemExit(f"test event {test_event!r} not in samples: {sorted(set(df['event']))}")
    if df["event"].nunique() < 2:
        raise SystemExit("need samples from at least 2 events to train and test on different floods")
    return df[df["event"] != test_event], df[df["event"] == test_event]


def fit(train: pd.DataFrame) -> HistGradientBoostingClassifier:
    model = HistGradientBoostingClassifier(
        max_iter=300, learning_rate=0.05, max_leaf_nodes=31, l2_regularization=1.0,
        categorical_features=[C.FEATURES.index(c) for c in C.CATEGORICAL], random_state=42,
    )
    return model.fit(train[C.FEATURES].astype(float), train["flood"].astype(int))


def evaluate(model, test: pd.DataFrame) -> dict:
    y = test["flood"].astype(int)
    p = model.predict_proba(test[C.FEATURES].astype(float))[:, 1]
    pred = (p >= 0.5).astype(int)
    return {
        "n_test": int(len(test)),
        "roc_auc": round(float(roc_auc_score(y, p)), 3),
        "avg_precision": round(float(average_precision_score(y, p)), 3),
        "precision@0.5": round(float(precision_score(y, pred, zero_division=0)), 3),
        "recall@0.5": round(float(recall_score(y, pred, zero_division=0)), 3),
        "f1@0.5": round(float(f1_score(y, pred, zero_division=0)), 3),
    }


def importance_chart(model, test: pd.DataFrame, path: str) -> dict:
    r = permutation_importance(model, test[C.FEATURES].astype(float), test["flood"].astype(int),
                               scoring="roc_auc", n_repeats=5, random_state=42)
    imp = dict(sorted(zip(C.FEATURES, r.importances_mean.round(4)), key=lambda kv: kv[1]))
    plt.figure(figsize=(6, 4))
    plt.barh(list(imp), list(imp.values()), color="#0B5FA5")
    plt.xlabel("Drop in ROC AUC when shuffled")
    plt.title("What drives the flood prediction")
    plt.tight_layout()
    plt.savefig(path, dpi=150)
    return {k: float(v) for k, v in imp.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test-event", default=list(C.EVENTS)[-1])
    args = ap.parse_args()

    paths = sorted(glob.glob(os.path.join(C.DATA_DIR, "samples_*.csv")))
    if not paths:
        raise SystemExit("no samples found: run `python build_dataset.py samples` first")
    train, test = split_by_event(load_samples(paths), args.test_event)
    model = fit(train)
    os.makedirs(C.MODEL_DIR, exist_ok=True)
    metrics = {"train_events": sorted(set(train["event"])), "test_event": args.test_event, **evaluate(model, test)}
    metrics["importance"] = importance_chart(model, test, os.path.join(C.MODEL_DIR, "importance.png"))

    # Refit on every event for the deployed model; the metrics above stay the honest held-out score.
    joblib.dump(fit(pd.concat([train, test])), os.path.join(C.MODEL_DIR, "flood_model.joblib"))
    with open(os.path.join(C.MODEL_DIR, "metrics.json"), "w") as f:
        json.dump(metrics, f, indent=2)
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
