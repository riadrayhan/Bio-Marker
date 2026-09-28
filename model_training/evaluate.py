"""
Sanity-check the exported TFLite by running it on the held-out test split.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.metrics import mean_absolute_error, roc_auc_score

from synthetic_data import FEATURES, generate, zscore_per_user

MODEL_PATH = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets" / "risk_model.tflite"


def main() -> None:
    if not MODEL_PATH.exists():
        raise SystemExit(f"Model not found at {MODEL_PATH}. Run train_model.py first.")

    df = zscore_per_user(generate(n_users=100, days_per_user=50, seed=99))
    X = df[FEATURES].astype("float32").values
    y = (df["phq9"] / 27.0).astype("float32").values

    interp = tf.lite.Interpreter(model_path=str(MODEL_PATH))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]

    preds = np.zeros(len(X), dtype=np.float32)
    for i, row in enumerate(X):
        interp.set_tensor(inp["index"], row.reshape(1, -1))
        interp.invoke()
        preds[i] = interp.get_tensor(out["index"])[0, 0]

    mae = mean_absolute_error(y, preds) * 27.0
    y_bin = (y * 27.0 >= 10).astype(int)
    auc = roc_auc_score(y_bin, preds)
    print(f"TFLite MAE (PHQ-9 scale): {mae:.2f}")
    print(f"TFLite screening AUC:     {auc:.3f}")


if __name__ == "__main__":
    main()

