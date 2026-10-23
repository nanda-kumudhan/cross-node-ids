#!/usr/bin/env python3
"""Build syscall n-gram features from the ADFA splits."""
import argparse
import hashlib
import json
from pathlib import Path

from sklearn.feature_extraction import DictVectorizer
from scipy.sparse import save_npz


def read_trace(path):
    tokens = Path(path).read_text().split()
    if not tokens or any(not token.isdecimal() for token in tokens):
        raise ValueError(f"Expected a nonempty numeric syscall trace: {path}")
    return [str(int(token)) for token in tokens]


def ngram_counts(tokens, n):
    if len(tokens) < n:
        raise ValueError(f"Trace shorter than n={n}")
    counts = {}
    for index in range(len(tokens) - n + 1):
        key = " ".join(tokens[index:index + n])
        counts[key] = counts.get(key, 0) + 1
    total = sum(counts.values())
    return {key: value / total for key, value in counts.items()}


def load_split(path, n):
    files = sorted(Path(path).rglob("*.txt"))
    if not files:
        raise ValueError(f"No .txt trace files in {path}")
    return files, [ngram_counts(read_trace(file), n) for file in files]


def prepare(train_dir, validation_dir, attack_dir, output_dir, n=3):
    if not 1 <= n <= 8:
        raise ValueError("n must be between 1 and 8")
    roots = [Path(path).resolve() for path in (train_dir, validation_dir, attack_dir)]
    for index, root in enumerate(roots):
        for other in roots[index + 1:]:
            if root == other or root in other.parents or other in root.parents:
                raise ValueError("Split directories must be distinct and must not overlap")
    splits = {name: load_split(root, n) for name, root in zip(("train", "validation", "attack"), roots)}
    seen = {}
    manifest = {"n": n, "unit": "whole_trace", "splits": {}, "duplicates_within_split": {}}
    for name, (files, _) in splits.items():
        records = []
        for path in files:
            canonical = " ".join(read_trace(path)).encode()
            digest = hashlib.sha256(canonical).hexdigest()
            if digest in seen and seen[digest] != name:
                raise ValueError(f"Trace content overlaps splits: {path} and {seen[digest]}")
            if digest in seen:
                manifest["duplicates_within_split"][name] = manifest["duplicates_within_split"].get(name, 0) + 1
            seen[digest] = name
            records.append({"path": str(path.relative_to(roots[("train", "validation", "attack").index(name)])), "sha256": digest})
        manifest["splits"][name] = records
    vectorizer = DictVectorizer(sparse=True, sort=True)
    matrices = {"train": vectorizer.fit_transform(splits["train"][1])}
    for name in ("validation", "attack"):
        matrices[name] = vectorizer.transform(splits[name][1])
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    for name, matrix in matrices.items():
        save_npz(output / f"{name}.npz", matrix)
        vocabulary = vectorizer.vocabulary_
        novelty = [sum(weight for gram, weight in features.items() if gram not in vocabulary)
                   for features in splits[name][1]]
        (output / f"{name}.novelty.json").write_text(json.dumps(novelty) + "\n")
    (output / "vocabulary.json").write_text(json.dumps(vectorizer.get_feature_names_out().tolist()) + "\n")
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for split in ("train", "validation", "attack"):
        parser.add_argument(f"--{split}", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--n", type=int, default=3)
    args = parser.parse_args()
    result = prepare(args.train, args.validation, args.attack, args.output, args.n)
    print(json.dumps({name: len(records) for name, records in result["splits"].items()}))
