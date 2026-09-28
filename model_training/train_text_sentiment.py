"""
train_text_sentiment.py
=======================

Trains a tiny on-device text-sentiment classifier and exports it as a
TFLite model that the Android app can drop into `app/src/main/assets/
text_sentiment.tflite`.

The Android side ([com.riad.bio_marker.ml.TextSentimentEngine]) currently
ships a lexicon-based fallback so the app builds and runs even before this
script has been executed. Once a TFLite asset is shipped, swap the
fallback for a `tflite-runtime` invocation with the same `(label, score)`
contract.

This script is intentionally small / dependency-light so it runs on a
CPU laptop in a few minutes. For real use you'd train on a labelled
journal / SMS / chat dataset (we cannot ship one — privacy).

Usage
-----
    python train_text_sentiment.py \
        --data data/sentiment.csv \
        --out  ../app/src/main/assets/text_sentiment.tflite

CSV format expected:
    text,label
    "i feel great today",POSITIVE
    "tired and angry",NEGATIVE
    "ok",NEUTRAL
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
from pathlib import Path


LABELS = ["NEGATIVE", "NEUTRAL", "POSITIVE"]


def load_csv(path: Path):
    texts, labels = [], []
    with path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            t = (row.get("text") or "").strip()
            l = (row.get("label") or "NEUTRAL").strip().upper()
            if not t or l not in LABELS:
                continue
            texts.append(t)
            labels.append(LABELS.index(l))
    return texts, labels


def build_and_train(texts, labels, vocab_size=4000, max_len=32, epochs=5):
    # Lazy imports so the file is importable for `--help` without TF.
    import numpy as np
    import tensorflow as tf
    from tensorflow.keras import layers, models

    tok = tf.keras.preprocessing.text.Tokenizer(num_words=vocab_size, oov_token="<oov>")
    tok.fit_on_texts(texts)
    seqs = tok.texts_to_sequences(texts)
    x = tf.keras.preprocessing.sequence.pad_sequences(seqs, maxlen=max_len, padding="post")
    y = np.array(labels, dtype="int32")

    model = models.Sequential([
        layers.Input(shape=(max_len,), dtype="int32"),
        layers.Embedding(vocab_size, 16, mask_zero=True),
        layers.GlobalAveragePooling1D(),
        layers.Dense(16, activation="relu"),
        layers.Dense(len(LABELS), activation="softmax"),
    ])
    model.compile(
        optimizer="adam",
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    model.fit(x, y, epochs=epochs, batch_size=64, validation_split=0.1, verbose=2)
    return model, tok


def export_tflite(model, out_path: Path):
    import tensorflow as tf
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite = converter.convert()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(tflite)
    print(f"wrote {out_path} ({len(tflite)/1024:.1f} KiB)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True,
                    help="CSV with `text,label` columns")
    ap.add_argument("--out", type=Path, default=Path("text_sentiment.tflite"))
    ap.add_argument("--vocab-size", type=int, default=4000)
    ap.add_argument("--max-len", type=int, default=32)
    ap.add_argument("--epochs", type=int, default=5)
    args = ap.parse_args()

    if not args.data.exists():
        print(f"data file not found: {args.data}", file=sys.stderr)
        sys.exit(1)

    texts, labels = load_csv(args.data)
    if len(texts) < 50:
        print(f"WARNING: only {len(texts)} rows — model will be poor", file=sys.stderr)
    print(f"loaded {len(texts)} rows")

    model, _ = build_and_train(
        texts, labels,
        vocab_size=args.vocab_size,
        max_len=args.max_len,
        epochs=args.epochs,
    )
    export_tflite(model, args.out)


if __name__ == "__main__":
    main()

