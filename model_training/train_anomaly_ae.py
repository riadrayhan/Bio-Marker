"""
Train a tiny autoencoder for trend anomaly detection on the four daily-target
metrics (sleep hrs, walking min, phone hrs, talking min).

Pipeline:
    1. Generate synthetic 30-day windows for N synthetic users.
    2. z-normalise each user's history.
    3. Fit a 4 -> 8 -> 4 dense autoencoder.
    4. Export TFLite -> ../app/src/main/assets/anomaly_ae.tflite

The on-device Kotlin wrapper [AnomalyDetector] currently uses a z-score
fallback. Once this asset is shipped, swap the inference call to use TFLite
(input shape `[1, 4]`, output = reconstruction). Reconstruction error per
metric is the per-feature anomaly score; mean reconstruction error is the
overall anomaly score. A threshold of ~0.7 (z-equivalent ~2σ) matches the
Kotlin fallback.

Run:
    python model_training/train_anomaly_ae.py --users 500 --epochs 30
"""
import argparse, os, numpy as np

def synth(num_users=500, days=30, seed=0):
    rng = np.random.default_rng(seed)
    out = []
    for _ in range(num_users):
        sleep = rng.normal(7.5, 0.7, days).clip(4, 10)
        walk = rng.normal(35, 12, days).clip(0, 120)
        phone = rng.normal(3.0, 0.8, days).clip(0.5, 8)
        talk = rng.normal(20, 8, days).clip(0, 90)
        out.append(np.stack([sleep, walk, phone, talk], axis=1))
    return np.concatenate(out, axis=0)  # [num_users*days, 4]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--users", type=int, default=500)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__),
                    "..", "app", "src", "main", "assets", "anomaly_ae.tflite"))
    args = ap.parse_args()

    try:
        import tensorflow as tf
    except ImportError:
        print("Install tensorflow first: pip install tensorflow"); return

    X = synth(args.users)
    mean = X.mean(0); std = X.std(0) + 1e-6
    Xn = (X - mean) / std

    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(4,)),
        tf.keras.layers.Dense(8, activation="relu"),
        tf.keras.layers.Dense(4),
    ])
    model.compile(optimizer="adam", loss="mse")
    model.fit(Xn, Xn, epochs=args.epochs, batch_size=64, verbose=2)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tfl = converter.convert()
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as f: f.write(tfl)
    print(f"Wrote {args.out} ({len(tfl)} bytes)")
    print(f"Normalisation mean={mean.tolist()}, std={std.tolist()}")
    print("Persist these (mean, std) into a Kotlin constant or assets/anomaly_ae_norm.json")

if __name__ == "__main__":
    main()

