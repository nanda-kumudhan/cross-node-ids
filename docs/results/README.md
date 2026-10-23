# Measurements — no results yet

For every real run record:

- UTC time, source/image digests and exact workload command.
- `uname -r`, CPU model, vCPU count, RAM and OS image ID per VM.
- Capabilities, ring-buffer size, queue caps, Kafka and clock configuration.
- Repeated baseline/agent CPU, memory and workload runtime measurements.
- Generated/received/high-watermark counts, kernel drops and reconciled gaps;
  sequence scopes/reset policy and any overlapping loss counters.
- Event-to-alert p50/p95, measurement clocks and measured skew/uncertainty.
- Scripted attack trials/detections and false alerts per rule per benign day.

Use separate VMs. Do not publish synthetic dashboard values as results. Baseline
tool comparisons must use the same workload/machine and disclose settings.
