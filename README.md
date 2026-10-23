# cross-node-ids

Side project for detecting activity across Linux hosts using eBPF events and
Kafka Streams. Currently contains the local Docker setup: Kafka, Fluent Bit
and Postgres. The agent and application services are still to be written.

Requires Docker with Compose, Make and Bash. No host Maven, Java or C packages
are needed for this setup.

```sh
cp .env.example .env
make up
make smoke
```

`make up` starts the containers and creates the `events.raw` topic. `make smoke`
queries Postgres, appends a sample JSON line to `deploy/logs/events.jsonl` and
checks that Fluent Bit sends it to Kafka. It also requires the `timeout` command.

Fluent Bit tails `deploy/logs/*.jsonl`. To read the topic:

```sh
make consume
```

Connections from the host use `localhost:29092` for Kafka and `localhost:5432`
for Postgres. Containers use `kafka:9092` and `postgres:5432`. Ports and database
credentials are set in `.env`; the defaults are for local development. Both
published ports bind to localhost.

```sh
make test   # validate Compose configuration; does not start containers
make ps     # container status
make logs   # follow logs
make down   # stop containers; keep stored data
```

Kafka data, Postgres data and Fluent Bit's read offsets use Docker volumes.
Input logs stay in `deploy/logs/` and are ignored by Git. Fluent Bit buffers up
to 1 GB while Kafka is unavailable; older queued data can be lost at that limit.
Changing database credentials after the first start does not update an existing
Postgres volume.

The Compose file is `deploy/compose.dev.yml`. Fluent Bit uses
`deploy/fluent-bit.conf` and `deploy/parsers.conf`. Image versions are pinned
in the Compose file.

```text
agent/             eBPF agent and gap checker
engine/            Java 21 / Spring Boot / Kafka Streams
rules/             detection rules and event fixtures
attacks/           attack simulations and benign workloads
tools/eventstorm/  event load generator
ml/                Colab notebook and model card
deploy/            Compose files, Fluent Bit config and Terraform
ui/                React view
docs/adr/          architecture decisions
docs/diagrams/     architecture and sequence diagrams
docs/results/      measurements, including kernel version and CPU
```

Files for the agent, engine, rules, CI and multi-node deployment are placeholders.
`make deploy` and `make destroy` exit with a message until Terraform is configured.

Configuration references: [Kafka image](https://hub.docker.com/r/apache/kafka),
[Fluent Bit tail input](https://docs.fluentbit.io/manual/data-pipeline/inputs/tail),
[Kafka output](https://docs.fluentbit.io/manual/data-pipeline/outputs/kafka),
[Postgres image](https://hub.docker.com/_/postgres).
