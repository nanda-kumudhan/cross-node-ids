# Learning from the implemented prototype

The original plan separated student-written core code from app scaffolding. The
non-ML MVP is now implemented with AI assistance. This guide points at the real
code so you can learn from it at your own pace. Runtime checks requiring root,
Docker and separate VMs remain distinct from compilation and fixture tests.

## Files to read in order

| Location | What to understand |
| --- | --- |
| `rules/fixtures/connection.jsonl` | Two labelled synthetic observations of the same connection |
| `schemas/event.schema.json` | The external event format and network requirements |
| `engine/.../Event.java` | Validation, event identity and normalized connection keys |
| `engine/.../DetectionRule.java` | Safe YAML loading and supported rule kinds |
| `engine/.../DetectionTopology.java` | Source timestamps, branches, rekeying, repartitioning and join |
| `engine/.../SequenceState.java` | Missing intervals, duplicates, bounded reconciliation and restarts |
| `engine/.../TelemetryProcessor.java` | Changelog-backed state and loss snapshots |
| `engine/.../DetectionTopologyTest.java` | Matches, non-matches, grace, loss and duplicate behavior |
| `agent/src/events.h` | Shared binary event between kernel and userspace |
| `agent/src/kernel_types.h` | Minimal named CO-RE field views |
| `agent/src/ids.bpf.c` | Exec/network hooks, successful return checks and failure maps |
| `agent/src/ids.c` | Loader, JSON escaping, clocks, sequences, heartbeats and shutdown |
| `tools/benchmark/` | Actual workload execution and measurement boundaries |

Java paths above are under `engine/src/main/java/dev/crossnode/ids/`, tests under
`engine/src/test/java/dev/crossnode/ids/`. The source is a prototype: learn its
stated limitations as well as the syntax. See [contracts](contracts.md).

## Small learning exercises

1. Run `make replay` with the local stack, then explain why both observations
   have the same tuple but different Kafka source keys.
2. Change a fixture source port and write a test expecting no join. Change only
   arrival order and expect the original match.
3. Explain why a timestamp extractor is needed and why grace does not synchronize
   clocks. Read the test that deliberately supplies a wrong broker timestamp.
4. Trace sequences `1,3,2,3`: one provisional gap, reconciliation, duplicate.
   Repeat under a new run ID and explain why the previous scope is separate.
5. Read the C event struct. Trace reserve → fill → submit → callback → JSON.
   Compile and run `--self-test` before attempting privileged attachment.
6. Explain why `tcp_v4_connect` returning zero is not enough to emit a successful
   connection. Read the syscall-exit check and the blocking-socket demo.
7. Add a harmless field through the shared struct, serialization and schema,
   then test its output without collecting command arguments.
8. Run the native smoke test on a VM and compare independently printed socket
   tuples with captured events. Follow the two-VM instructions afterwards.
9. Explain which losses sequence gaps cannot see and why a missing final marker
   prevents a complete end-to-end claim.

## Folder map

```text
agent/          C probes/loader, build, serialization tests and offline gap CLI
engine/         Spring Boot, Kafka Streams, persistence, API and Java tests
rules/          YAML definitions; disabled future SSH sketches; replay fixture
schemas/        Event, rule and dashboard contracts
ui/             React view, synthetic replay and Nginx
scripts/        Docker integration and native privileged smoke check
attacks/        Harmless blocking TCP / temporary-execution demo
tools/          Replay, YAML/TLS helpers, gap auditing and Go workload generator
  benchmark/    Collection overhead and alert-emission latency tools
deploy/         Compose, Fluent Bit, Terraform and optional Helm/AKS
ml/             Unchanged optional preprocessing experiment
docs/          Design, setup, validation and real measurement records
.venv/          Local Python dependencies, ignored by Git
```

## What is deliberately excluded

The implemented connection rule is not an SSH login or a causal command chain.
Adding those needs trusted authentication/session context and process ancestry.
IPv6, nonblocking/io_uring network coverage, NAT and Kubernetes enrichment are
separate extensions. ML was left untouched. No runtime or benchmark number is
established by synthetic fixtures. [Validation](validation.md) states what ran.
