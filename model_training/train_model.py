"""
Train the Bio_Marker LSTM risk model and export it to TFLite.

Architecture (matches spec):
    Input  (7, 15)
    LSTM(64)
    Dropout(0.3)
    Dense(32, relu)
    Dense(1,  sigmoid)        # risk score 0..1

Usage:
    python train_model.py
    python train_model.py --data-csv path/to/real_data.csv

Output:
    ../app/src/main/assets/risk_model.tflite
    ../app/src/main/assets/feature_importance.json   (SHAP, optional)
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.metrics import mean_absolute_error, roc_auc_score
from sklearn.model_selection import GroupShuffleSplit

from synthetic_data import FEATURES, WINDOW_DAYS, build_windows, generate, zscore_per_user

ASSETS_DIR = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets"
MODEL_PATH = ASSETS_DIR / "risk_model.tflite"
SHAP_PATH = ASSETS_DIR / "feature_importance.json"


def build_model() -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(WINDOW_DAYS, len(FEATURES)), name="features")
    x = tf.keras.layers.LSTM(64)(inputs)
    x = tf.keras.layers.Dropout(0.3)(x)
    x = tf.keras.layers.Dense(32, activation="relu")(x)
    out = tf.keras.layers.Dense(1, activation="sigmoid", name="risk")(x)
    model = tf.keras.Model(inputs, out, name="risk_lstm")
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss="mse", metrics=["mae"])
    return model


def load_dataframe(path: str | None) -> pd.DataFrame:
    if path:
        print(f"Loading real data from {path}")
        df = pd.read_csv(path)
    else:
        print("Generating synthetic data ...")
        df = generate()
    return zscore_per_user(df)


def split(df: pd.DataFrame):
    X, y, groups = build_windows(df)
    splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=0)
    train_idx, test_idx = next(splitter.split(X, y, groups=groups))
    return X[train_idx], y[train_idx], X[test_idx], y[test_idx]


def export_tflite(model: tf.keras.Model, dst: Path) -> None:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    tflite_bytes = converter.convert()
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(tflite_bytes)
    print(f"Wrote {dst} ({len(tflite_bytes) / 1024.0:.1f} KB)")


def export_shap(model: tf.keras.Model, X_sample: np.ndarray) -> None:
    try:
        import shap  # type: ignore
    except ImportError:
        print("SHAP not installed; skipping feature importance export.")
        return
    background = X_sample[: min(50, len(X_sample))]
    try:
        explainer = shap.GradientExplainer(model, background)
        sv = explainer.shap_values(background)
        if isinstance(sv, list):
            sv = sv[0]
        importance = np.abs(sv).mean(axis=(0, 1))
        SHAP_PATH.write_text(json.dumps(
            {f: float(v) for f, v in zip(FEATURES, importance)}, indent=2
        ))
        print(f"Wrote {SHAP_PATH}")
    except Exception as e:  # pragma: no cover
        print(f"SHAP export failed: {e}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-csv", default=None)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--no-shap", action="store_true")
    args = parser.parse_args()

    df = load_dataframe(args.data_csv)
    Xtr, ytr, Xte, yte = split(df)
    print(f"Train: {Xtr.shape}, Test: {Xte.shape}")

    model = build_model()
    model.summary()

    model.fit(
        Xtr, ytr,
        validation_data=(Xte, yte),
        epochs=args.epochs,
        batch_size=args.batch_size,
        verbose=2,
    )

    pred = model.predict(Xte, verbose=0).ravel()
    mae = mean_absolute_error(yte, pred) * 27.0
    yte_bin = (yte * 27.0 >= 10).astype(int)
    if 0 < yte_bin.sum() < len(yte_bin):
        auc = roc_auc_score(yte_bin, pred)
    else:
        auc = float("nan")
    print(f"Test MAE on PHQ-9 scale: {mae:.2f}")
    print(f"Screening AUC (PHQ-9>=10): {auc:.3f}")

    export_tflite(model, MODEL_PATH)
    if not args.no_shap:
        export_shap(model, Xte)
    print("Done. Rebuild the Android app to pick up the new model.")


if __name__ == "__main__":
    main()

