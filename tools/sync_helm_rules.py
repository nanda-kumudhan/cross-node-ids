#!/usr/bin/env python3
"""Keep the Helm chart's rules in sync with rules/."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sync(check=False):
    source = ROOT / "rules"
    target = ROOT / "deploy/helm/cross-node-ids/rules"
    expected = {path.name: path.read_bytes() for path in source.glob("*.yml")}
    actual = {path.name: path.read_bytes() for path in target.glob("*.yml")}
    if check:
        if actual != expected:
            raise SystemExit("Helm rule copies are stale; run python tools/sync_helm_rules.py")
        return
    if actual.keys() - expected.keys():
        raise SystemExit("Unexpected Helm rule files; review and remove obsolete copies manually")
    target.mkdir(parents=True, exist_ok=True)
    for name, content in expected.items():
        (target / name).write_bytes(content)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    sync(parser.parse_args().check)
