# Validation status

The non-ML MVP is implemented; runtime/measurement claims remain deliberately
separate from compilation and fixture tests. Validation performed on 2026-10-23:

- C userspace loader and eBPF object compile with warnings treated as errors.
- Three agent tests pass without BPF privileges: JSON schema/escaping, sequences,
  restart identity, append behavior and explicit output/argument failures.
- All 22 Java tests pass; Java compiles for release 21 and packages a Spring Boot JAR. Topology tests
  cover event-time joins, tuple/node mismatch, window edges, grace, duplicates,
  provisional reconciliation, run scopes, missing-prefix/tail evidence and loss
  annotations. A Spring context test verifies actual detection startup wiring
  with broker startup disabled. The local JDK is OpenJDK 27, not the CI JDK 21.
- All 13 Python schema/rule, offline accounting and benchmark tests pass inside `.venv`.
- Four UI data tests and the React production build pass on Node 26.
- Two Go generator tests pass; its sequence cap matches the safe-integer schema.
- Dev/app/core/sensor Compose configurations validate. Helm lint passes.
- DigitalOcean/Azure Terraform validation and recursive formatting pass after
  adding the pinned agent image and lab TCP firewall rule. No plan/apply ran.
- Replay fixture validation, Python compilation and Bash syntax checks pass.
- The demo scripts pass a real loopback client/server endpoint-agreement check
  and harmless temporary-execution check outside the socket-restricted sandbox.
- The benchmark harness baseline-only mode ran 20 operations to verify execution
  and artifact output. This is a harness smoke check, not an agent-overhead result.

## Not verified

Docker daemon access was denied even outside the sandbox. Native eBPF load failed
with EPERM; the explicit sudo retry required a password. Therefore no Docker
image-build/startup result, real Kafka/Postgres integration, BPF verifier/attach
success, native event capture, two-VM match or cloud deployment is claimed.

`make integration` now tests raw-event correlation and processor restart recovery
in addition to direct alert/loss ingestion. It is configured in CI but has not
run here. `sudo bash scripts/verify-runtime.sh` checks real exec/connect/accept
on an isolated VM. Both commands must pass on an accessible runtime before the
project is described as working end to end.

No actual overhead, skew, alert-latency distribution, detection rate, 24-hour
benign run or load-loss measurement is published. Use the benchmark harness and
record raw evidence before adding numbers to the README. The existing ML files
were not changed; no ML training or evaluation was performed.
