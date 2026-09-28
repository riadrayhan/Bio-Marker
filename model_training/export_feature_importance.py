"""
Compute permutation feature importance for the legacy 12-feature dense
risk model and export it as JSON.

This is the lightweight, dependency-free alternative to SHAP — it works on
the dense model produced by `train_legacy.py` and writes the JSON shape that
[MODEL_README.md](app/src/main/assets/MODEL_README.md) advertises:

    {
      "<feature_name>": <float, mean |Δscore| when the feature is shuffled>,
      ...
    }

Usage (from this folder, with the .venv activated):

    python export_feature_importance.py

Output: ../app/src/main/assets/feature_importance.json
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from train_legacy import LEGACY_FEATURES, synth, zscore_per_user

ASSETS_DIR = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets"
TFLITE_PATH = ASSETS_DIR / "risk_model.tflite"
JSON_PATH = ASSETS_DIR / "feature_importance.json"


def predict(interpreter, x: np.ndarray) -> np.ndarray:
    """Run the TFLite model on a (N, 12) batch by feeding rows one-by-one
    (the exported model has a static batch dim of 1)."""
    in_idx = interpreter.get_input_details()[0]["index"]
    out_idx = interpreter.get_output_details()[0]["index"]
    out = np.empty((x.shape[0], 1), dtype=np.float32)
    for i in range(x.shape[0]):
        interpreter.set_tensor(in_idx, x[i : i + 1].astype(np.float32))
        interpreter.invoke()
        out[i] = interpreter.get_tensor(out_idx)
    return out.ravel()


def main() -> None:
    if not TFLITE_PATH.exists():
        raise SystemExit(
            f"{TFLITE_PATH} not found. Run train_legacy.py first."
        )

    # Lazy import so the script still parses cleanly if TF isn't installed.
    import tensorflow as tf

    print("Loading test split ...")
    df = zscore_per_user(synth())
    # Use a deterministic 20% sample for speed.
    rng = np.random.default_rng(0)
    idx = rng.choice(len(df), size=min(2000, len(df)), replace=False)
    x = df.iloc[idx][LEGACY_FEATURES].astype("float32").values

    print(f"Loading model from {TFLITE_PATH}")
    interpreter = tf.lite.Interpreter(model_path=str(TFLITE_PATH))
    # Resize to a batch of 1 (default already, but make it explicit).
    interpreter.allocate_tensors()

    print("Computing baseline predictions ...")
    base = predict(interpreter, x)

    print("Computing permutation importance per feature ...")
    importances: dict[str, float] = {}
    for fi, name in enumerate(LEGACY_FEATURES):
        x_perm = x.copy()
        rng.shuffle(x_perm[:, fi])  # shuffle this feature in place
        perturbed = predict(interpreter, x_perm)
        importances[name] = float(np.mean(np.abs(perturbed - base)))
        print(f"  {name:<24s} {importances[name]:.4f}")

    # Normalise so the largest contributor = 1.0 — convenient for UI weighting.
    peak = max(importances.values()) or 1.0
    importances_norm = {k: round(v / peak, 4) for k, v in importances.items()}

    JSON_PATH.write_text(json.dumps(importances_norm, indent=2))
    print(f"\nWrote {JSON_PATH}")


if __name__ == "__main__":
    main()

