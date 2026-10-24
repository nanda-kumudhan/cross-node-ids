#!/usr/bin/env python3
"""Repeated collection-only baseline/agent measurements. Requires a test Linux VM."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import statistics
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from loss import account


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent / 100
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def command_output(command):
    try:
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=5)
        return result.stdout.strip() if result.returncode == 0 else 'unavailable'
    except (OSError, subprocess.TimeoutExpired):
        return 'unavailable'


def workload(count, workers):
    def execute(_):
        subprocess.run(['/usr/bin/true'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    started = time.monotonic_ns()
    with ThreadPoolExecutor(max_workers=workers) as executor:
        for _ in executor.map(execute, range(count)):
            pass
    return (time.monotonic_ns() - started) / 1e9


def process_sample(pid):
    fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
    cpu = (int(fields[11]) + int(fields[12])) / os.sysconf('SC_CLK_TCK')
    rss = 0
    for line in Path(f'/proc/{pid}/status').read_text().splitlines():
        if line.startswith('VmRSS:'):
            rss = int(line.split()[1]) * 1024
    return cpu, rss


def agent_run(args, iteration):
    log = args.output / f'agent-{iteration}.jsonl'
    diagnostics = args.output / f'agent-{iteration}.stderr'
    samples = []
    finished = threading.Event()
    with diagnostics.open('w') as errors:
        agent = subprocess.Popen([str(ROOT / 'agent/bin/ids-agent'), '--node', 'benchmark-node',
            '--object', str(ROOT / 'agent/bin/ids.bpf.o'), '--output', str(log),
            '--ring-bytes', str(args.ring_bytes)], stderr=errors, stdout=subprocess.DEVNULL)
        monitor = None
        try:
            deadline = time.monotonic() + 15
            while True:
                if agent.poll() is not None:
                    raise RuntimeError(f'Agent exited during startup; inspect {diagnostics}')
                lines = log.read_text().splitlines() if log.exists() else []
                if any(json.loads(line).get('ready') for line in lines):
                    break
                if time.monotonic() > deadline:
                    raise TimeoutError('Agent did not report successful attachment')
                time.sleep(0.05)
            start_cpu, _ = process_sample(agent.pid)

            def monitor_process():
                while not finished.wait(0.02):
                    try:
                        samples.append(process_sample(agent.pid)[1])
                    except (OSError, ValueError, IndexError):
                        return

            monitor = threading.Thread(target=monitor_process, daemon=True)
            monitor.start()
            elapsed = workload(args.count, args.workers)
            end_cpu, rss = process_sample(agent.pid)
            samples.append(rss)
        finally:
            finished.set()
            if monitor:
                monitor.join(timeout=1)
            if agent.poll() is None:
                agent.send_signal(signal.SIGTERM)
            try:
                agent.wait(timeout=10)
            except subprocess.TimeoutExpired:
                agent.kill()
                agent.wait()
        if agent.returncode != 0:
            raise RuntimeError(f'Agent failed; inspect {diagnostics}')
    with log.open() as source:
        events = [json.loads(line) for line in source]
    if not events or events[-1].get('final') is not True:
        raise RuntimeError('Run lacks a clean closing watermark; do not claim complete accounting')
    target = [event for event in events if event['type'] == 'exec' and event.get('executable') == '/usr/bin/true']
    delays = [event['userspace_delay_ns'] / 1e6 for event in events if 'userspace_delay_ns' in event]
    return dict(mode='agent', elapsed_seconds=elapsed, completed=args.count,
                achieved_operations_per_second=args.count / elapsed,
                agent_cpu_seconds=end_cpu - start_cpu, sampled_peak_rss_bytes=max(samples, default=0),
                target_exec_observations=len(target), target_exec_shortfall=max(0, args.count - len(target)),
                loss=account(events), event_to_userspace_ms=dict(n=len(delays), p50=percentile(delays, 50), p95=percentile(delays, 95)),
                raw_events=str(log))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New directory; existing results are never overwritten')
    parser.add_argument('--count', type=int, default=2000)
    parser.add_argument('--workers', type=int, default=1)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--ring-bytes', type=int, default=1048576)
    parser.add_argument('--baseline-only', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.count <= 1000000 or not 1 <= args.workers <= 64 or not 1 <= args.repeats <= 30:
        parser.error('Require count 1..1000000, workers 1..64, repeats 1..30')
    if args.ring_bytes < 4096 or args.ring_bytes > 64 * 1024 * 1024 or args.ring_bytes & (args.ring_bytes - 1):
        parser.error('ring-bytes must be a power of two in 4096..67108864')
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    binaries = {name: hashlib.sha256((ROOT / f'agent/bin/{name}').read_bytes()).hexdigest()
                for name in ('ids-agent', 'ids.bpf.o') if (ROOT / f'agent/bin/{name}').exists()}
    cpu_model = next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                      if line.startswith('model name')), 'unavailable')
    metadata = dict(scope='collection-only; no Kafka event-to-alert benchmark', utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                    command=sys.argv, kernel=platform.release(), machine=platform.machine(), cpu=cpu_model,
                    logical_cpus=os.cpu_count(), memory=Path('/proc/meminfo').read_text().splitlines()[0],
                    os_release=Path('/etc/os-release').read_text(), source_revision=command_output(['git', 'rev-parse', 'HEAD']),
                    source_status=command_output(['git', 'status', '--short']), binary_sha256=binaries,
                    chrony_tracking=command_output(['chronyc', 'tracking']), count=args.count, workers=args.workers,
                    repeats=args.repeats, ring_bytes=args.ring_bytes,
                    note='Target exec counts require an isolated VM without unrelated /usr/bin/true workloads. RSS is sampled, not an exact maximum.')
    (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    runs = []
    try:
        for iteration in range(args.repeats):
            order = ['baseline'] if args.baseline_only else (['baseline', 'agent'] if iteration % 2 == 0 else ['agent', 'baseline'])
            for mode in order:
                if mode == 'agent':
                    row = agent_run(args, iteration)
                else:
                    elapsed = workload(args.count, args.workers)
                    row = dict(mode=mode, elapsed_seconds=elapsed, completed=args.count, achieved_operations_per_second=args.count / elapsed)
                runs.append(dict(iteration=iteration, **row))
                (args.output / 'runs.json').write_text(json.dumps(runs, indent=2) + '\n')
        baseline = statistics.median(row['elapsed_seconds'] for row in runs if row['mode'] == 'baseline')
        agent_times = [row['elapsed_seconds'] for row in runs if row['mode'] == 'agent']
        summary = dict(status='complete', baseline_median_seconds=baseline,
                       agent_median_seconds=statistics.median(agent_times) if agent_times else None,
                       median_runtime_overhead_percent=100 * (statistics.median(agent_times) / baseline - 1) if agent_times else None,
                       event_to_alert_latency='unmeasured; collect real alerts and clock uncertainty separately')
        (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(summary, indent=2))
    except Exception as error:
        (args.output / 'failure.json').write_text(json.dumps(dict(status='failed', error=str(error)), indent=2) + '\n')
        raise


if __name__ == '__main__':
    main()
