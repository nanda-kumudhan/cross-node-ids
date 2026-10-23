# Development and deployment

## Local Compose

Run `cp .env.example .env`, then `make up` for Kafka, Fluent Bit and Postgres.
`make smoke` checks shipping and SQL. `make app` builds and starts the Java
service and React view. Open http://localhost:8088; choose replay or Live API.
Live data is empty until alerts/loss are produced. No detector is installed.
`make down` stops the stack and retains volumes.

Kafka, Postgres and the API bind to localhost:29092, :5432 and :8080. Local Kafka
is plaintext on loopback and its Docker network. The remote file uses a separate
mTLS listener. Both configurations have one broker and are not highly available.
Fluent Bit tails `deploy/logs/*.jsonl`, retains offsets and buffers up to 1 GB.
Old queued data can be lost at the cap. Topics are events.raw, alerts and
metrics.loss. Keep node-key partition ordering stable during loss experiments.

## Validation

Use Java 21, Node >=24, Python 3.12, Go >=1.23, Terraform >=1.9 and Helm 3/4.
In an activated Python venv:

```sh
pip install -r tools/requirements.txt -r ml/requirements.txt
npm ci --prefix ui
terraform -chdir=deploy/terraform init -backend=false
terraform -chdir=deploy/terraform/azure init -backend=false
make test
```

The Maven wrapper pins Maven 3.9.9. Tests cover configuration, schemas, ingestion
boundaries, UI data handling, preprocessing and load generation. They do not
establish detection correctness, actual eBPF loss or overhead. CI publishes the
three implemented images to GHCR; the placeholder agent is excluded.

`make integration` builds an isolated Compose project on random loopback ports,
checks Kafka alert/loss ingestion, idempotent storage, stale snapshots, separate
agent-run scopes and the UI reverse proxy, then removes its own containers and
volumes. It needs Docker access. It is included in CI; local execution is not
claimed when Docker permissions prevent it.

## VM deployment

1. Push the source and publish images through CI. Make the repository and GHCR
   packages public so cloud-init can pull without embedding credentials.
2. Obtain a pinned numeric Ubuntu 24.04 image ID, review price/quota, and supply
   an existing SSH key and a narrow administrator IPv4 CIDR.
3. Copy `deploy/terraform/terraform.tfvars.example` to `terraform.tfvars` in that
   directory. Replace every example, including the source SHA and image digests.
4. Export `DIGITALOCEAN_TOKEN`; run `make plan`, then `make deploy` when ready.
   Terraform requests approval before applying. `make destroy` removes the VMs.

Default topology is a core VM plus two sensors in a VPC. Cloud-init installs
Docker and chrony, checks out the pinned source and starts Compose. Core hosts
Kafka, Postgres, engine and UI. Sensors initially run Fluent Bit only. The
agent profile is disabled until the C agent is built.

Terraform completion does not mean cloud-init or chrony is ready. Check
`cloud-init status --wait`, `/var/log/cloud-init-output.log`, container health
and `chronyc tracking` per VM. Use the `dashboard_tunnel` output for UI access.
Public inbound access is admin SSH only. Sensors may reach the core TLS broker
and each other's SSH ports for a controlled chain simulation.

A short-lived test CA signs per-sensor clients and the broker's actual VPC IP
SAN. Certificate verification and client authentication are required externally;
broker/controller traffic on the core Docker network is plaintext. Test-CA
clients are trusted without per-topic ACL isolation. Renewal is not automated.

Private keys, passwords and cloud-init user data exist in Terraform state and
the provider's user-data API. Sensitive marking is not encryption. Use a secured
state backend for shared/cloud work; local state is ignored. The CA signing key
is retained outside the broker mount after bootstrap. Secure the provisioning
account and destroy test resources after use.

`bash tools/generate_tls.sh BROKER_IP` generates local manual-test certificates
without overwriting an existing directory. Kafka UID 1000 needs read/traverse
access inside its mounted core directory. Protect the host's parent secret
directory; copy only each matching client cert/key/CA to its sensor. Do not make
all host secrets world-readable to fix a mount permission error.

Enable `--profile agent` only after setting AGENT_IMAGE and verifying its output
contract. The proposed BPF/PERFMON capabilities depend on kernel, attach method,
LSM, seccomp and resource limits. Validate on the target kernel; no compatibility
claim is made for the unfinished loader. Do not silently fall back to privileged
production containers.

## Kubernetes and Azure

Kafka stays external. A private DO VPC is not reachable from AKS by default:
establish routed connectivity, DNS and advertised listeners first. These files
do not provision a cross-cloud VPN. For kind/k3s choose a broker endpoint the
workers can reach. On an isolated local network only, explicitly setting
`kafka.securityProtocol=PLAINTEXT` is available.

Create the namespace and secrets before Helm installation:

```sh
kubectl create namespace ids
# Keep password.txt outside the repo; its content is your chosen DB password.
kubectl -n ids create secret generic ids-postgres --from-file=password=/secure/password.txt
# client.pem is a PKCS#8 private key followed by its certificate.
kubectl -n ids create secret generic ids-kafka-tls \
  --from-file=ca.crt=/secure/ca.crt --from-file=client.pem=/secure/client.pem
python3 tools/sync_helm_rules.py
helm upgrade --install crossnode deploy/helm/cross-node-ids -n ids \
  --set engine.image=YOUR_REGISTRY/engine --set engine.tag=COMMIT_SHA \
  --set ui.image=YOUR_REGISTRY/ui --set ui.tag=COMMIT_SHA \
  --set kafka.bootstrapServers=REACHABLE_BROKER:9094 \
  --set postgres.existingSecret=ids-postgres --set kafka.existingTlsSecret=ids-kafka-tls \
  --atomic --wait --timeout 10m
kubectl -n ids port-forward svc/crossnode-ids-ui 8088:8080
```

The chart provides one Java replica, one UI replica and persistent single-instance
Postgres. No backups/HA or Kafka operator. Helm uninstall retains the Postgres
PVC; review and delete it when its data can be discarded. Sync authoritative
rules with the helper; CI rejects stale chart copies.

For AKS, fill `deploy/terraform/azure/terraform.tfvars` after checking
[current pricing](https://azure.microsoft.com/pricing/details/kubernetes-service/)
and subscription region/size quotas. No cost estimate is asserted. Authenticate
Terraform with Azure CLI/OIDC, then run `make azure-plan` and `make azure-deploy`.
This provisions AKS, ACR and a GitHub-federated managed identity.

Set GitHub environment `azure` variables AZURE_CLIENT_ID, AZURE_TENANT_ID,
AZURE_SUBSCRIPTION_ID, AZURE_RESOURCE_GROUP, AKS_CLUSTER_NAME, ACR_NAME,
ACR_SERVER and KAFKA_BOOTSTRAP_SERVERS. Use Terraform outputs where available.
Restrict the environment to trusted branches/reviewers. Its OIDC subject is
`repo:OWNER/REPO:environment:azure`. No long-lived Azure password is stored.
The deployment identity has registry push and administration rights within this
cluster; narrow that role if adding unrelated workloads.

After secrets and networking exist, run the manual Azure workflow. It builds
engine/UI images, pushes to ACR and deploys through Helm with rollback on failure.
`make azure-destroy` removes the test environment. A Free AKS management tier
still charges for workers, disks and networking.
