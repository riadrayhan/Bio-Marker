"""
Synthetic data generator for the Bio_Marker LSTM risk model.

Produces (user, day, 15-feature) tuples plus a per-day PHQ-9 label in [0, 27].
Feature order MUST match `DailyFeatureStore.Daily.toFloatArray()` in the app.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

FEATURES = [
    "motion_energy_mean",
    "motion_energy_std",
    "sample_entropy",
    "step_count",
    "sleep_duration_hrs",
    "sleep_irregularity_hrs",
    "location_entropy",
    "screen_time_min",
    "screen_time_delta7d",
    "late_night_usage_min",
    "social_app_ratio",
    "voice_f0_hz",
    "voice_jitter",
    "voice_energy_mean",
    "phone_unlock_count",
]

WINDOW_DAYS = 7  # LSTM input length


def generate(n_users: int = 400, days_per_user: int = 90, seed: int = 42) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows = []

    for u in range(n_users):
        latent = float(np.clip(rng.normal(8, 6), 0, 27))
        base_motion = rng.uniform(0.6, 2.5)
        base_steps = rng.uniform(3000, 11000)
        base_sleep = rng.uniform(6.0, 8.0)
        base_screen = rng.uniform(120, 360)
        base_voice_f0 = rng.uniform(110, 220)
        base_voice_e = rng.uniform(0.10, 0.25)

        for d in range(days_per_user):
            latent = float(np.clip(latent + rng.normal(0, 0.5), 0, 27))
            sev = latent / 27.0

            motion_mean = max(0.0, base_motion * (1 - 0.4 * sev) + rng.normal(0, 0.1))
            motion_std = max(0.0, motion_mean * 0.4 + rng.normal(0, 0.05))
            sample_entropy = max(0.0, 1.5 * (1 - 0.5 * sev) + rng.normal(0, 0.1))
            steps = max(0, int(base_steps * (1 - 0.55 * sev) + rng.normal(0, 800)))
            sleep_hrs = float(np.clip(base_sleep - 2.5 * sev + rng.normal(0, 0.6), 2, 11))
            sleep_irr = float(np.clip(0.5 + 1.5 * sev + rng.normal(0, 0.3), 0, 4))
            loc_ent = float(np.clip(2.0 * (1 - 0.6 * sev) + rng.normal(0, 0.2), 0, 4))
            screen = float(np.clip(base_screen * (1 + 0.6 * sev) + rng.normal(0, 30), 0, 900))
            screen_delta = rng.normal(0, 30) + 60 * sev
            late_night = float(np.clip(rng.normal(5, 3) + 30 * sev, 0, 240))
            social_ratio = float(np.clip(0.2 - 0.15 * sev + rng.normal(0, 0.05), 0, 1))
            voice_f0 = float(np.clip(base_voice_f0 * (1 - 0.15 * sev) + rng.normal(0, 8), 60, 320))
            voice_jit = float(np.clip(0.005 + 0.015 * sev + rng.normal(0, 0.003), 0, 0.05))
            voice_e = float(np.clip(base_voice_e * (1 - 0.5 * sev) + rng.normal(0, 0.02), 0, 0.4))
            unlocks = int(np.clip(rng.normal(60, 15) + 40 * sev, 0, 250))

            rows.append({
                "user_id": u, "day": d,
                "motion_energy_mean": motion_mean,
                "motion_energy_std": motion_std,
                "sample_entropy": sample_entropy,
                "step_count": steps,
                "sleep_duration_hrs": sleep_hrs,
                "sleep_irregularity_hrs": sleep_irr,
                "location_entropy": loc_ent,
                "screen_time_min": screen,
                "screen_time_delta7d": screen_delta,
                "late_night_usage_min": late_night,
                "social_app_ratio": social_ratio,
                "voice_f0_hz": voice_f0,
                "voice_jitter": voice_jit,
                "voice_energy_mean": voice_e,
                "phone_unlock_count": unlocks,
                "phq9": latent,
            })

    return pd.DataFrame(rows)


def zscore_per_user(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    for c in FEATURES:
        mu = out.groupby("user_id")[c].transform("mean")
        sigma = out.groupby("user_id")[c].transform("std").replace(0, 1)
        out[c] = (out[c] - mu) / sigma
    return out


def build_windows(df: pd.DataFrame, window: int = WINDOW_DAYS):
    """Return X of shape (N, window, 15), y of shape (N,) (PHQ-9 / 27), and group ids."""
    X, y, groups = [], [], []
    df = df.sort_values(["user_id", "day"])
    for uid, g in df.groupby("user_id"):
        feats = g[FEATURES].astype("float32").values
        labels = (g["phq9"].values / 27.0).astype("float32")
        for i in range(window, len(g)):
            X.append(feats[i - window:i])
            y.append(labels[i])
            groups.append(uid)
    return np.asarray(X), np.asarray(y), np.asarray(groups)


if __name__ == "__main__":
    df = zscore_per_user(generate())
    X, y, groups = build_windows(df)
    print("X:", X.shape, "y:", y.shape)
