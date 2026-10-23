# Cross-node IDS

I'm building a system to spot lateral movement between Linux machines using
eBPF events and Kafka Streams. The aim is to connect activity across hosts and
measure how many events the pipeline loses under load.

## So far

- Spring Boot service that reads alerts from Kafka and stores them in Postgres.
- React dashboard with sample replay data and a live API view.
- YAML rule definitions and validation, a Go load generator and ML preprocessing.
- Docker Compose, CI image builds, Terraform and an optional Helm/AKS setup.

The eBPF agent, rule evaluation, gap detector and cross-node join still need to
be built. The dashboard data is synthetic; there are no benchmark results yet.

## Run locally

You need Docker Compose and Make.

```sh
cp .env.example .env
make up
make smoke
make app
```

Open http://localhost:8088. Live mode will be empty until alerts are sent to Kafka.
Use `make down` to stop the stack and keep its data.

## Tests and deployment

`make test` runs the checks. `make integration` tests the app in a separate
Compose stack. Dependencies and setup are in [docs/deployment.md](docs/deployment.md).

That guide also covers the VM and Kubernetes setups. Event formats are in
[docs/contracts.md](docs/contracts.md), and ML work is in [ml/](ml/).
