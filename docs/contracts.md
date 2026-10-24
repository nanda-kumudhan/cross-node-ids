# Integration contracts

## Runtime and rules

`CoreDetectionModule` installs the topology when `IDS_DETECTION_ENABLED=true`.
Compose enables it by default; a bare Spring application defaults to ingestion-only.
Readiness includes the detector's actual Kafka Streams state, not only the flag.
Rule YAML is read at startup with safe construction and duplicate keys forbidden.
Supported enabled kinds are `single` and `connection`; unsupported enabled kinds
fail startup. Disabled SSH/session sketches are not evaluated. Change a rule by
restarting the engine; window/grace come from that rule, not separate defaults.

The Python schema validator checks definitions, IDs and references. Java tests
exercise actual rule behavior using TopologyTestDriver. The connection rule
requires successful IPv4 TCP, different nodes, equal normalized client/server
4-tuples and event timestamps at most five seconds apart (inclusive). Ten-second
grace admits late arrivals relative to stream time. These are initial settings,
not a measured clock/lateness budget. Rekeyed branches explicitly repartition
before the join. Real broker co-partitioning/recovery still needs integration tests.

A TCP tuple can be reused; overlapping reuse within the window can produce
ambiguous matches. NAT, proxies, IPv6 and session attribution are outside MVP.
The temporary-directory rule matches the captured filename prefix, not every
possible execution of a temporary file. Generic rules use `attack: []`; no shell
or authentication technique is inferred from a path or port alone.

## Events and identity

`schemas/event.schema.json` defines JSON envelopes, including probe-specific
network fields. Use UTC RFC3339 timestamps. The Kafka key must equal `node`;
unkeyed/mismatched input fails processing. A payload timestamp extractor supplies
event time. Invalid input fails instead of silently disappearing. Fix/replay the
source or use a reviewed recovery strategy; there is no automatic dead-letter queue.

Sequence scope is `(node, boot_id, agent_run_id)`. Sequences start at one and are
limited to JavaScript's safe-integer maximum, as are dashboard counters. Process
identity is `(node, boot_id, pid, process_start_ns)` and survives loader restarts.
The agent reads the group leader's monotonic start time, not its exec time.

Both connect and accept use client endpoint as `src_*`, server as `dst_*`.
`success`, `protocol=tcp` and `address_family=ipv4` are explicit. See
[agent/README.md](../agent/README.md) for hook selection, exclusions, byte escaping
and timestamp conversion. Arguments are omitted. Kernel BTF/monotonic time does
not correct clock skew: retain chrony readings on all participating machines.

## Loss and duplication

The agent allocates sequences in one userspace reader, including heartbeat
records. A heartbeat's `high_watermark` equals its own sequence. A startup marker
precedes attachment; `ready=true` follows all successful attachments. Each second
and on clean shutdown, counters are sampled. Shutdown detaches, drains and writes
`final=true`. Each new loader uses fresh maps and a new run ID.

`kernel_drops` counts ring reservations that failed before userspace could assign
a sequence. `capture_errors` counts explicit read/bookkeeping failures. Sequence
gaps describe missing records after assignment. These describe different stages;
never claim that their sum captures all unobserved events or all unsupported hooks.
A lost closing marker/crash leaves the final tail unknown. Zero gaps alone does
not prove zero collection loss. Counters are cumulative per agent run.

Java state is persisted in a changelog-backed key-value store before rekeying.
Missing ranges are intervals, not expanded integers. Within a 65,536-sequence
lookback they reconcile when late records arrive. Older gaps become finalized;
records below the retained floor increment `late_unreconciled` and cannot establish
new unique evidence or alerts. More than 4096 intervals finalizes the oldest.
Unique received counts include heartbeats and exclude duplicates/too-old arrivals.
Initial missing prefixes are accounted for from sequence one. Events following
an observed final watermark at a higher sequence fail processing.

There is a 10,000-scope cap per source task; reaching it fails explicitly. This
prototype retains scopes until a reviewed archival/reset with a new application
ID. Do not silently evict/reset accounting or change raw partition counts during
an experiment. Changing application ID replays evidence and must be a deliberate
experiment reset. Source-retention loss or deleted changelogs can prevent recovery.

`metrics.loss` supplies cumulative snapshots. `revision` orders updates even when
event times arrive out of order or multiple snapshots share a millisecond. The
DB prefers greater revisions; a legacy unversioned snapshot cannot replace a
versioned one. Legacy-only snapshots use timestamps. Provisional/finalized gaps,
capture errors, highest sequence, closing status and late counts remain explicit.

Duplicate evidence is suppressed within the retained horizon before either rule
runs. Stable IDs also make alert redelivery idempotent in Postgres. `known_loss`
means a kernel-drop/capture-error increase observed near rule evidence before
emission, not proof that a particular session lost events. Provisional sequence
gaps do not set this flag; their separate metrics can reconcile later. Later
telemetry does not amend existing alerts. Missing
required accept evidence produces no connection alert: the loss panel remains
the source of missing-telemetry status.

## Alerts, persistence and UI

`alerts` carries the dashboard alert contract plus original evidence, an emission
timestamp and a synthetic flag. `timestamp` is the latest contributing event;
`detected_at` is the processor wall clock. Their difference is emission latency,
not DB or browser delivery time, and needs measured cross-host clock uncertainty.
No generic SSH attack chain is produced by these two rules.

Kafka consumers acknowledge after DB writes. A crash between write and Kafka
ack can redeliver; the primary key deduplicates. This is not an atomic Kafka/DB
transaction. Malformed output and DB outages retry without advancing that partition.
Retention, backups and a durable dead-letter policy remain operational extensions.

`GET /api/dashboard` returns the latest 200 alerts and recorded loss scopes.
No authentication is implemented; keep loopback/ClusterIP bindings and use SSH
or port-forwarding. Static Pages serves explicitly synthetic replay without a
live API. The UI draws supplied node paths; it does not independently infer chains.
