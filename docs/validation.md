# Local validation

Validated during implementation on 2026-10-23:

- Maven wrapper 3.9.9: Java sources compile for release 21; four JUnit tests pass;
  executable Spring Boot JAR packaged. Host runtime used OpenJDK 27.
- React/Vite production build and four UI data-handling tests pass on Node 26.
- Six Python schema/rule tests and two preprocessing tests pass on Python 3.14.
- Two Go generator tests pass; five emitted events pass the event schema.
- Dev/app/core/sensor Compose files validate with test environment values.
- DigitalOcean and Azure Terraform configurations validate with pinned providers;
  formatting passes. No plan/apply or cloud deployment was executed.
- Helm lint and TLS-mode rendering pass; eight Kubernetes objects parse.
- Test CA/server/client certificates generate, chain verification passes and the
  broker certificate contains its configured IP and DNS SANs.
- Pinned new base-image tags and the Azure Helm/kubelogin references exist in
  their registries. Maven distribution SHA-256 is recorded in wrapper properties.

Not verified locally: Docker image builds, container startup, real Kafka/DB
integration, browser interactions or Kubernetes/cloud runtime deployment. Docker
socket access was denied and noninteractive sudo required a password. The CI
integration job runs `make integration` in an isolated Compose project.

No real agent/detector, sequence-loss accounting, overhead/skew benchmark or ML
evaluation exists yet. Synthetic replay is not a measurement result. CI specifies
Java 21, Python 3.12, Node 24 and Go 1.24 to validate the intended build runtimes.
