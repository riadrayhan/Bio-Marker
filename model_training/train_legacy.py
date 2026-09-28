"""
Train the *legacy* 12-feature Dense risk model that the current on-device
`TFLiteRiskEngine.kt` actually consumes.

Why this script exists alongside `train_model.py`:
- `train_model.py` builds an LSTM(7, 15) — the future spec model. That requires
  `tensorflow-lite-select-tf-ops` on Android and a (1, 7, 15) input pipeline
  that hasn't been wired into the engine yet.
- `train_legacy.py` matches the existing engine: input shape (1, 12),
  output shape (1, 1) sigmoid. No SELECT_TF_OPS dependency required.

Use this script today to produce a working `risk_model.tflite`. Switch to
`train_model.py` once the LSTM engine swap (Slice 5) is done.

Output: ../app/src/main/assets/risk_model.tflite
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.metrics import mean_absolute_error, roc_auc_score
from sklearn.model_selection import GroupShuffleSplit

# Order MUST match `FeatureSpec.NAMES` in app/.../ml/FeatureSpec.kt
LEGACY_FEATURES = [
    "motion_energy_mean",
    "motion_energy_std",
    "motion_anomaly_rate",
    "keystroke_count",         # log1p in app
    "avg_inter_key_latency",
    "backspace_rate",
    "hour_sin",
    "hour_cos",
    "voice_energy_mean",
    "voice_energy_std",
    "voice_zcr_mean",
    "voice_pause_ratio",
]
N_FEATURES = len(LEGACY_FEATURES)
ASSETS_DIR = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets"
MODEL_PATH = ASSETS_DIR / "risk_model.tflite"


def synth(n_users: int = 400, days: int = 60, seed: int = 7) -> pd.DataFrame:
    """Generate per-day rows with realistic depressive correlations."""
    rng = np.random.default_rng(seed)
    rows = []
    for u in range(n_users):
        latent = float(np.clip(rng.normal(8, 6), 0, 27))
        b_motion = rng.uniform(0.6, 2.5)
        b_lat = rng.uniform(180, 320)
        b_keys = rng.uniform(150, 1200)
        for _ in range(days):
            latent = float(np.clip(latent + rng.normal(0, 0.6), 0, 27))
            sev = latent / 27.0
            motion_mean = max(0.0, b_motion * (1 - 0.4 * sev) + rng.normal(0, 0.1))
            motion_std = max(0.0, motion_mean * 0.4 + rng.normal(0, 0.05))
            anomaly = float(np.clip(0.01 + 0.08 * sev + rng.normal(0, 0.01), 0, 0.5))
            keys = max(0.0, b_keys * (1 - 0.5 * sev) + rng.normal(0, 50))
            keys_log = float(np.log1p(keys))
            lat = max(80.0, b_lat * (1 + 0.6 * sev) + rng.normal(0, 15))
            bs_rate = float(np.clip(0.03 + 0.12 * sev + rng.normal(0, 0.02), 0, 0.6))
            hour = int(rng.integers(0, 24))
            angle = 2 * np.pi * hour / 24.0
            has_voice = rng.random() < 0.4
            if has_voice:
                ve = max(0.0, 0.18 * (1 - 0.4 * sev) + rng.normal(0, 0.02))
                vs = max(0.0, 0.06 * (1 - 0.5 * sev) + rng.normal(0, 0.01))
                vz = max(0.0, 0.12 * (1 - 0.3 * sev) + rng.normal(0, 0.015))
                vp = float(np.clip(0.15 + 0.4 * sev + rng.normal(0, 0.05), 0, 0.9))
            else:
                ve = vs = vz = vp = 0.0
            rows.append({
                "user_id": u,
                "motion_energy_mean": motion_mean,
                "motion_energy_std": motion_std,
                "motion_anomaly_rate": anomaly,
                "keystroke_count": keys_log,
                "avg_inter_key_latency": lat,
                "backspace_rate": bs_rate,
                "hour_sin": float(np.sin(angle)),
                "hour_cos": float(np.cos(angle)),
                "voice_energy_mean": ve,
                "voice_energy_std": vs,
                "voice_zcr_mean": vz,
                "voice_pause_ratio": vp,
                "phq9": latent,
            })
    return pd.DataFrame(rows)


def zscore_per_user(df: pd.DataFrame) -> pd.DataFrame:
    """Replicate on-device personalisation: z-score per user, except hour_*."""
    cols = [c for c in LEGACY_FEATURES if not c.startswith("hour_")]
    out = df.copy()
    for c in cols:
        mu = out.groupby("user_id")[c].transform("mean")
        sigma = out.groupby("user_id")[c].transform("std").replace(0, 1)
        out[c] = (out[c] - mu) / sigma
    return out


def build_model() -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(N_FEATURES,), name="features")
    x = tf.keras.layers.Dense(32, activation="relu")(inputs)
    x = tf.keras.layers.Dropout(0.2)(x)
    x = tf.keras.layers.Dense(16, activation="relu")(x)
    out = tf.keras.layers.Dense(1, activation="sigmoid", name="risk")(x)
    model = tf.keras.Model(inputs, out, name="risk_dense")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss="mse",
        metrics=["mae"],
    )
    return model


def main() -> None:
    print("Generating synthetic data ...")
    df = zscore_per_user(synth())
    splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=0)
    tr, te = next(splitter.split(df, groups=df["user_id"]))
    Xtr = df.iloc[tr][LEGACY_FEATURES].astype("float32").values
    ytr = (df.iloc[tr]["phq9"].values / 27.0).astype("float32")
    Xte = df.iloc[te][LEGACY_FEATURES].astype("float32").values
    yte = (df.iloc[te]["phq9"].values / 27.0).astype("float32")
    print(f"Train: {Xtr.shape}, Test: {Xte.shape}")

    model = build_model()
    model.summary()
    model.fit(Xtr, ytr, validation_data=(Xte, yte),
              epochs=30, batch_size=256, verbose=2)

    pred = model.predict(Xte, verbose=0).ravel()
    mae = mean_absolute_error(yte, pred) * 27.0
    yte_bin = (yte * 27.0 >= 10).astype(int)
    auc = roc_auc_score(yte_bin, pred) if 0 < yte_bin.sum() < len(yte_bin) else float("nan")
    print(f"Test MAE on PHQ-9 scale: {mae:.2f}")
    print(f"Screening AUC (PHQ-9>=10): {auc:.3f}")

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    MODEL_PATH.write_bytes(tflite_bytes)
    print(f"Wrote {MODEL_PATH} ({len(tflite_bytes) / 1024.0:.1f} KB)")
    print("Rebuild the Android app to bundle the new model.")


if __name__ == "__main__":
    main()

