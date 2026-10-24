#!/usr/bin/env python3
"""Summarize real alert emission wall-clock deltas; requires measured clock uncertainty."""
import argparse
import datetime
import json
import math
from pathlib import Path
from run import percentile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot', type=Path, help='Saved /api/dashboard JSON containing real alerts')
    parser.add_argument('--clock-uncertainty-ms', type=float, required=True)
    args = parser.parse_args()
    if not math.isfinite(args.clock_uncertainty_ms) or args.clock_uncertainty_ms < 0:
        parser.error('clock uncertainty must be nonnegative')
    data = json.loads(args.snapshot.read_text())
    delays = []
    for alert in data['alerts']:
        if alert.get('synthetic') is not False or not alert.get('evidence'):
            raise ValueError('Synthetic or unproven alert provenance; refusing a measured-latency summary')
        emitted = datetime.datetime.fromisoformat(alert['detected_at'].replace('Z', '+00:00'))
        observed = datetime.datetime.fromisoformat(alert['timestamp'].replace('Z', '+00:00'))
        delays.append((emitted - observed).total_seconds() * 1000)
    if not delays:
        raise ValueError('No real alerts to measure')
    if min(delays) < -args.clock_uncertainty_ms:
        raise ValueError('Negative wall-clock delta exceeds the supplied uncertainty budget')
    print(json.dumps(dict(n=len(delays), p50_ms=percentile(delays, 50), p95_ms=percentile(delays, 95),
                         clock_uncertainty_ms=args.clock_uncertainty_ms,
                         definition='Latest rule-evidence timestamp to detector emission; excludes dashboard/storage delivery'), indent=2))


if __name__ == '__main__':
    main()
