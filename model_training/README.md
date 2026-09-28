# Bio_Marker — Risk Model Training

End-to-end pipeline that produces `risk_model.tflite` matching the contract
in `app/src/main/assets/MODEL_README.md`.

Two paths are provided:

1. **Synthetic** (default, no data access required) — generates 50k plausible
   feature vectors with realistic correlations between sedentary motion, slow
   typing, and elevated PHQ-9, then trains an MLP. Use this to verify the
   pipeline compiles and ships an end-to-end working `.tflite`.

2. **Real** (placeholder) — point `--data-csv` at a CSV produced from
   DAIC-WOZ / GLOBEM / StudentLife with the same 8 columns + a `phq9` label
   column. The training code is identical.

## Quick start

```powershell
cd model_training
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python train_model.py        # synthetic data, writes ../app/src/main/assets/risk_model.tflite
```

After the script finishes, rebuild the Android app — the next inference call
will use the real TFLite model instead of the heuristic fallback. You should
see `engine: tflite-risk-v1` on the dashboard.

## Architecture

```
features (8) ──► Dense(32) ReLU ──► Dropout(0.2)
              ──► Dense(16) ReLU ──► Dropout(0.2)
              ──► Dense(1)  Sigmoid ──► risk score 0..1
```

~700 trainable parameters, < 5 KB on disk after quantization, < 1 ms inference
on a Snapdragon 4-class device.

## Files

- `requirements.txt` — pinned deps
- `synthetic_data.py` — generator with realistic clinical correlations
- `train_model.py` — train + evaluate + TFLite export + copy to assets
- `evaluate.py` — load the exported `.tflite` and print AUC / sensitivity

