# ADR 0001: Keep detection separate from the app setup

Status: accepted.

The app setup covers alert storage, the dashboard, tooling and deployment.
The agent, gap detector, rules and correlation logic are still to do.

The Java service defaults to ingestion-only. DetectionModule is required before
Streams starts. Synthetic replay is labelled; schema validation does not stand
in for detection tests. Local Compose uses loopback plaintext; remote shipping
uses mTLS. Kubernetes uses an explicitly reachable external Kafka broker.

This lets me test the app before the detector is ready. The MVP still needs
real probes and correlation working end to end. Final measurements need separate VMs.
