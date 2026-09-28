"""
Train a multi-output linear regression that suggests personalised daily
targets (sleep, walking, phone, talking) from the user's last 7-day means
and weekday one-hot.

Input  shape: (11,)   = [sleep_mean, walk_mean, phone_mean, talk_mean,
                         dow_mon, dow_tue, dow_wed, dow_thu, dow_fri,
                         dow_sat, dow_sun]
Output shape: (4,)    = [sleep_target, walk_target, phone_target, talk_target]

Output -> ../app/src/main/assets/target_regression.tflite
"""
import argparse, os, numpy as np

def synth(N=2000, seed=0):
    rng = np.random.default_rng(seed)
    sleep_m = rng.normal(7.0, 1.0, N).clip(4, 10)
    walk_m = rng.normal(30, 10, N).clip(5, 120)
    phone_m = rng.normal(3.5, 1.2, N).clip(0.5, 8)
    talk_m = rng.normal(18, 7, N).clip(0, 90)
    dow = rng.integers(0, 7, N)
    one_hot = np.eye(7)[dow]
    X = np.concatenate([np.stack([sleep_m, walk_m, phone_m, talk_m], axis=1), one_hot], axis=1)
    # Targets: lightly improved versions of the user's mean (the "what a
    # gentle nudge would look like" target).
    y_sleep = (sleep_m * 0.7 + 8.0 * 0.3).clip(6, 9)
    y_walk = (walk_m * 0.6 + 45 * 0.4).clip(20, 90)
    y_phone = (phone_m * 0.6 + 2.5 * 0.4).clip(1.5, 5)
    y_talk = (talk_m * 0.6 + 25 * 0.4).clip(10, 60)
    Y = np.stack([y_sleep, y_walk, y_phone, y_talk], axis=1)
    return X.astype(np.float32), Y.astype(np.float32)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2000)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__),
                    "..", "app", "src", "main", "assets", "target_regression.tflite"))
    args = ap.parse_args()
    try:
        import tensorflow as tf
    except ImportError:
        print("Install tensorflow first: pip install tensorflow"); return

    X, Y = synth(args.n)
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(11,)),
        tf.keras.layers.Dense(4),  # plain linear regression (no activation)
    ])
    model.compile(optimizer="adam", loss="mse")
    model.fit(X, Y, epochs=40, batch_size=64, verbose=2)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tfl = converter.convert()
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as f: f.write(tfl)
    print(f"Wrote {args.out} ({len(tfl)} bytes)")

if __name__ == "__main__":
    main()

