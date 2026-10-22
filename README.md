# cross-node-ids

Final year project for collecting eBPF telemetry from multiple nodes and seeing
what's going on across them. The plan is to use Spring Boot and Kafka Streams
for the backend, with Postgres keeping the data we want to query later.

Just the folder setup for now. Nothing runs yet.

## Rough data flow

```text
eBPF agents on each node
        |
        v
collector -> Kafka -> stream processor -> Kafka -> API + persistence -> Postgres
                                                       |
                                                       v
                                                 React dashboard
```

Each event should carry a node ID and timestamp so we can tell where it came
from and line things up across nodes. Topic names and event formats still need
sorting out.

## Folders

```text
agent/
  bpf/                 eBPF programs running in the kernel
  src/                 userspace code for reading and sending events
  include/             headers shared by the agent code
services/
  collector/           Spring Boot service receiving events and sending to Kafka
  stream-processor/    Kafka Streams app for processing events across nodes
  api/                 Spring Boot service saving results and serving queries
shared/
  schemas/             event formats used between the agent and services
db/
  migrations/          versioned Postgres schema changes
  seeds/               small bits of sample data for local development
dashboard/             possible React dashboard
infra/
  docker/              local containers and Compose setup
  k8s/                 Kubernetes manifests if we get that far
scripts/               setup and development helpers
docs/                  design notes and experiment results
tests/
  integration/         checks for the pipeline working together
```

Service-specific tests can live inside each service once the apps are created.
The `.gitkeep` files just make Git keep the empty folders; remove them as real
files go in.

## Starting point

- Get one agent sending events through the collector into Kafka.
- Add the stream processor and save its results in Postgres through the API service.
- Try it with a few nodes and check the IDs and timestamps survive the trip.
- Add a dashboard once there's something useful to show. Kubernetes can wait
  until the local setup works.

Keep local credentials in an ignored `.env` file. Commit a `.env.example` with
dummy values when there's actual config to share.
