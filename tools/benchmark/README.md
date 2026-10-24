# Collection benchmark

Run on an isolated Linux x86-64 test VM after verifying actual agent attachment.
The first command is build-only. Native loading usually needs root or validated
BPF/PERFMON capabilities plus a compatible kernel/LSM configuration.

```sh
make agent-build
sudo .venv/bin/python tools/benchmark/run.py --output docs/results/run-001 \
  --count 2000 --workers 1 --repeats 3
```

Repeat with controlled worker counts and a smaller `--ring-bytes 4096` to seek
reservation failures. A workload may not saturate the ring; report what happens.
Baseline/agent order alternates. Output includes exact command, source status,
binary hashes, CPU/kernel/OS, chrony output, raw events, completed operations,
achieved rate, runtime overhead, sampled agent RSS, userspace CPU time, per-scope
loss snapshots and event-to-userspace delay. Probe CPU time is reflected in
workload overhead but is not fully attributed to the userspace CPU counter.

Counts include heartbeats. Kernel drops, capture errors and downstream gaps are
separate. This harness reads the local file, so its gap results cover local
serialization, not Kafka delivery. Target exec shortfall is an independent check
requiring no unrelated `/usr/bin/true` workloads. Final heartbeat loss or a crash
prevents a complete close. Failed startup writes `failure.json`, not a successful
overhead result. `--baseline-only` can exercise the harness without BPF access.

To measure alert emission latency separately, save a real `/api/dashboard`
snapshot and record chrony offsets/uncertainty on the emitting and collecting
VMs. Supply an uncertainty budget derived from those readings:

```sh
.venv/bin/python tools/benchmark/alert_latency.py dashboard.json \
  --clock-uncertainty-ms YOUR_MEASURED_BUDGET
```

This measures latest evidence to alert emission, not browser polling latency.
It rejects synthetic replay. The API contains at most 200 latest alerts: retain
the alerts topic for longer runs rather than claiming this is a complete sample.
Pin the cloud OS image and record image digests alongside these artifacts.
