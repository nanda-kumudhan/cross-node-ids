# Cross-node IDS

A student prototype for correlating Linux telemetry across hosts. A C/eBPF agent
writes JSON, Fluent Bit ships it to Kafka, and Java Kafka Streams produces alerts
and loss snapshots for a Postgres-backed React dashboard.

## Implemented

- C agent for successful exec and blocking IPv4 TCP connect/accept, with process
  identity, omitted arguments, sequence numbers, heartbeats and failure counters.
- YAML-driven temporary-directory execution and cross-node connection rules.
- Event-time, tuple-keyed window join; persistent sequence reconciliation and
  duplicate suppression before joining; stable alert IDs and evidence.
- CLI/replay tools, controlled demo traffic and a collection benchmark harness.
- Docker, CI images, TLS shipping and VM provisioning; optional existing Helm/AKS.

C/native and BPF compilation, serialization tests and fixture-based Java tests
have been exercised. **Live BPF loading, Docker integration and two-VM behavior
remain unverified here because runtime access is unavailable.** No measured
overhead, detection rate, clock skew or event-loss result is published.

A connection match is an observed TCP relationship, not proof of authentication
or malicious movement. SSH session/attack-chain sketches remain disabled. ML is
unchanged preprocessing-only work; it is outside this implementation.

## Run the fixture pipeline

With working Docker access, Compose and Make:

```sh
cp .env.example .env  # first setup only
make up
make smoke
make app
make replay
make alerts
```

Open http://localhost:8088 and select Live API. `make replay` rebases a labelled
synthetic fixture to now with a new run ID; the result is not a measured attack.
`make down` stops containers while retaining data. Detection is enabled by default
in Compose; set `IDS_DETECTION_ENABLED=false` for ingestion-only operation.
Old malformed records from an earlier development stack can stop the detector;
see the recovery notes in [deployment](docs/deployment.md).

## Native collector and tests

Use a Linux x86-64 test VM. Install C prerequisites listed in [agent/](agent/README.md).
Python dependencies stay local:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements.txt
make test-agent
make test-engine
npm ci --prefix ui
make test-ui test-go
```

`make test-python` additionally checks the existing ML preprocessing and needs
its requirements already installed. `make test` also needs initialized Terraform
providers and Helm. The Maven wrapper targets Java 21. Tests do not establish
privileged kernel compatibility or cloud deployment success.

On an isolated VM with root access, `sudo bash scripts/verify-runtime.sh` checks
real exec and matching loopback network events. It retains its artifacts and
fails if attachment/events are missing. Then follow the
[two-VM demo](docs/deployment.md#two-monitored-vms) and
[benchmark instructions](tools/benchmark/README.md).

The default Terraform topology is two monitored sensors plus one core VM.
Deploying requires your source/image digests and credentials; no cloud resources
have been created. See [contracts](docs/contracts.md), [validation](docs/validation.md),
[scope](docs/scope.md) and the [code walkthrough](docs/learning-guide.md).
