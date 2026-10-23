# Eventstorm

`go run . -count 10000 -rate 1000 -node fixture-a -boot-id run-001 > events.jsonl`
generates synthetic shipping/ingestion traffic. Sequence numbers start at 1;
each invocation defaults to a unique run ID (`-run-id` overrides it). This does
not exercise eBPF. Boot identity and producer run identity remain separate.

`go run . -mode exec -count 1000 -rate 100` executes `/usr/bin/true` for
real exec probe traffic. `-mode connect -target 127.0.0.1:2222` requires an
existing local listener. Workload modes do not manufacture telemetry.
Connect mode accepts only literal loopback IPs. Run workloads on your test VM.

The rate is a pacing request, not measured throughput. Operations are sequential;
exec overhead and the scheduler limit actual rates. `-rate 0` removes pacing.
No concurrent fanout, command-line argument collection or remote scanning.
Measure real throughput with the separate hand-written benchmark harness.
