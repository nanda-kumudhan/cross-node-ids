# ADR 0001: Keep detection separate from app ingestion

Status: accepted; implementation updated.

The API, dashboard persistence and Kafka consumers can run independently of the
agent and detector. `DetectionModule` supplies the topology; `CoreDetectionModule`
now implements the two supported rule kinds. A bare app remains ingestion-only;
Compose enables detection explicitly. Readiness checks Streams state when enabled.

This separation permits fixture tests without kernel privileges and schema/API
checks without a broker. It does not substitute for live eBPF, Kafka/DB integration
or multi-VM measurements. Synthetic replay stays labelled. Local Kafka is
plaintext on loopback/Docker networks; remote sensor shipping uses mTLS.

ML, SSH session attribution and Kubernetes enrichment remain separate work.
