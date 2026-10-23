# Integration contracts

The service implements alert ingestion, persistence and a dashboard API.
Detection, eBPF collection and gap accounting are still to do.
`IDS_DETECTION_ENABLED=false` is the default. Enabling detection without a
`DetectionModule` fails startup. The join-window/grace settings are inputs for
the future module, not evidence that a windowed join already runs.

## Events and identity

`schemas/event.schema.json` defines the envelope. Use UTC RFC3339 timestamps.
The Kafka key is `node`. Sequence scope is `(node, boot_id, agent_run_id)`:
`boot_id` identifies the kernel boot and `agent_run_id` changes on loader restart.
Start sequences at 1; never reuse them in one scope. Process identity is
`(node, boot_id, pid, process_start_ns)`, independent of loader restarts.

Connect/accept correlation needs successful syscall returns, normalized TCP
local/remote address/port tuples, and address family. The extensible event schema
does not yet enforce all probe-specific fields. VMs with direct connections are
the MVP; NAT is out of scope. An accept is not an authenticated SSH login.
The onward-hop rule needs a trustworthy authentication/session signal and process
ancestry in addition to exec/connect/accept. That signal is not implemented.

Convert monotonic kernel time to wall-clock in userspace and document the method.
Measure chrony offsets on every VM. Kernel timestamps and grace windows do not
fix host clock skew. Redact command-line arguments by default.

## Loss

`metrics.loss` carries cumulative snapshots matching `#/$defs/loss` in
`schemas/dashboard.schema.json`. The store retains the newest snapshot per
node/boot/agent-run scope. Dashboard counters must fit a JavaScript safe integer.

Userspace-assigned sequence numbers cannot reveal events that never reached the
ring-buffer reader. Count failed kernel reservations separately. If sequence
allocation occurs before reservation, explain whether kernel drops also appear
in gaps; never add overlapping counters. Missing first/last events need
startup/high-watermark/heartbeat evidence. Late events need bounded gap
reconciliation. Zero observed gaps does not establish zero loss.

The dashboard displays supplied snapshots; it does not calculate gaps. Document
kernel-counter reset/baseline behavior consistently across agent restarts.

## Alerts and persistence

`alerts` carries JSON matching `#/$defs/alert` in the dashboard schema. Stable
unique IDs make redelivery idempotent in Postgres. Acknowledgment follows the DB
write. A crash between write and Kafka acknowledgment can redeliver; the primary
key deduplicates. This is not an atomic Kafka/Postgres transaction.

Malformed records and DB outages retry without advancing that partition. This
blocks the partition until corrected; no records are silently skipped. A durable
dead-letter policy, retention and archival remain operational work.

`GET /api/dashboard` returns live mode, generation time, detection status, the
latest 200 alerts and all recorded loss scopes. The API has no authentication;
keep it behind loopback/ClusterIP and use SSH tunnels or port-forwarding.
`ui/public/replay.json` is synthetic, explicitly labelled data. Replay imports
stay in the browser. Static GitHub Pages has no live API.

## Rules

Validation checks schema, duplicate YAML keys, IDs and sequence references.
These are not detection tests. TopologyTestDriver is included for the future
topology and replay fixtures. An executable in `/tmp` is not proof
of maliciousness or shell use; its broad T1059 mapping is provisional. SSH
context uses [T1021.004](https://attack.mitre.org/techniques/T1021/004/).
