"""
Train K-Means (K=3) on weekly per-weekday metric vectors and export the
centroids as JSON to ../app/src/main/assets/weekly_kmeans.json.

The on-device WeeklyClusterer uses an in-Kotlin k-means by default; once
this asset is shipped, swap to nearest-centroid using these fixed centroids
(removes per-device clustering noise and gives consistent labels).

Schema:
    {
      "k": 3,
      "feature_means": [...],
      "feature_stds":  [...],
      "centroids": [[z_sleep, z_walk, z_phone, z_talk], ...],
      "labels": ["Balanced", "High-screen low-rest", "Low-energy quiet"]
    }
"""
import argparse, json, os, numpy as np

def synth(N=600, seed=0):
    rng = np.random.default_rng(seed)
    out = []
    for _ in range(N):
        kind = rng.integers(0, 3)
        if kind == 0:        # balanced
            row = [rng.normal(7.5, .3), rng.normal(40, 5), rng.normal(2.8, .4), rng.normal(22, 4)]
        elif kind == 1:      # high-screen / low-rest
            row = [rng.normal(5.5, .5), rng.normal(20, 5), rng.normal(5.5, .8), rng.normal(15, 4)]
        else:                # low-energy / quiet
            row = [rng.normal(7.0, .5), rng.normal(10, 4), rng.normal(2.0, .5), rng.normal(8, 3)]
        out.append(row)
    return np.array(out, dtype=np.float32)

def kmeans(X, k=3, iters=50, seed=0):
    rng = np.random.default_rng(seed)
    idx = rng.choice(len(X), size=k, replace=False)
    C = X[idx].copy()
    for _ in range(iters):
        d = ((X[:, None, :] - C[None, :, :]) ** 2).sum(-1)
        a = d.argmin(1)
        for j in range(k):
            sel = X[a == j]
            if len(sel): C[j] = sel.mean(0)
    return C, a

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__),
                    "..", "app", "src", "main", "assets", "weekly_kmeans.json"))
    args = ap.parse_args()

    X = synth()
    mean = X.mean(0); std = X.std(0) + 1e-6
    Xn = (X - mean) / std
    C, _ = kmeans(Xn, k=3)
    # Heuristic label assignment based on z-signs.
    labels = []
    for c in C:
        s, w, p, t = c.tolist()
        if p > 0.5 and s < 0:        labels.append("High-screen low-rest")
        elif w < -0.3 and t < -0.3:  labels.append("Low-energy quiet")
        else:                        labels.append("Balanced")

    payload = {
        "k": 3,
        "feature_means": mean.tolist(),
        "feature_stds": std.tolist(),
        "centroids": C.tolist(),
        "labels": labels,
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)
    print(f"Wrote {args.out}")

if __name__ == "__main__":
    main()

