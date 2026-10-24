# A scope I can finish and explain

The aim is a student systems project with inspectable evidence: collect a small
set of Linux events, correlate a connection across two nodes, and measure the
pipeline. It is not a replacement for an established security product.

## Corrections to the original proposal

| Original expectation | Practical replacement | Reason |
| --- | --- | --- |
| Three-node SSH attack chain first | Match one TCP connection across two monitored VMs first | Connection correlation is useful and testable before session attribution. |
| Two probes do everything | Two network event types for the first correlation; exec added separately | Entry/return bookkeeping may need several attachments. Count event types separately from probes. |
| accept means SSH login | accept means a TCP connection was accepted | Authentication requires another signal; ownership of later commands requires ancestry/session tracking. |
| Node B runs something suspicious, therefore A caused it | Report two observations until a process/session relationship is proved | A time window alone cannot establish causality on a busy host. |
| Sequence numbers give exact end-to-end loss | Separate kernel reservation failures, downstream sequence gaps and unexplained uncertainty | Userspace cannot number an event it never receives. Initial/tail loss needs high-watermarks. |
| An overflowed chain is still detected with known loss | Annotate detections supported by surviving evidence; otherwise report incomplete telemetry | Required missing evidence can make detection impossible. |
| exec/connect/accept feeds the ADFA model | Keep ADFA as a separate optional offline experiment | Three event types are not a full syscall sequence. |
| Every rule needs an ATT&CK ID | Leave `attack: []` for generic observations | A temporary path does not demonstrate command-interpreter abuse. |
| Helm and AKS take another 10–15 hours | Defer Kubernetes/Azure until the VM demo and measurements work | Networking, credentials, debugging and unfamiliar tooling do not have a reliable fixed budget. |
| Everything fits 90–120 hours | Treat estimates as planning guesses and track actual time | First-time eBPF and stateful stream debugging can consume the original budget alone. |

The new `cross-node-connection.yml` expresses the first correlation. It has no
attack mapping and low severity. Both SSH/session rules are disabled. The two enabled kinds now have a Java evaluator and fixture tests; the disabled
sketches have no implementation.

MITRE defines [T1059](https://attack.mitre.org/techniques/T1059/) around command
interpreters and [T1021.004](https://attack.mitre.org/techniques/T1021/004/) around
SSH use. Those describe evidence requirements, not tags to attach to all events.

## Finish these milestones in order

1. A fixture-based connection join with positive and negative TopologyTestDriver
   tests; a CLI consumer displays its output.
2. A C exec collector and ring-buffer reader on one Linux VM. Use the
   [libbpf-bootstrap example](https://github.com/libbpf/libbpf-bootstrap), credit
   it, and pin the upstream revision when bringing it into the repository.
3. Successful IPv4 TCP connect/accept collection in a controlled lab; real
   matching observations across two monitored VMs.
4. Agent JSON → Fluent Bit → Kafka → detector → CLI. Add the simple exec rule
   and let the existing storage/dashboard consume the generated alerts.
5. Kernel drop snapshots, reconciled downstream gaps, repeated baseline/agent
   runs and p50/p95 latency with clock uncertainty recorded.
6. A short demo, honest README, raw results and an explanation of failure cases.

An early prototype is allowed to use a native root-run collector on an isolated
test VM. Restricted capabilities/container packaging are a subsequent task to
validate on that kernel. Do not claim least privilege before testing it.

The existing cloud configuration needs **two sensor VMs plus one core VM** to
monitor two nodes. The core is not currently a sensor. Local VMs are sufficient;
renting cloud machines is optional until the final deployment exercise.

If a week 4–5 review still has no join or network collector, stop adding features.
Get the fixture join and one successful network pair working before proceeding.
This is a review point, not a guaranteed completion date. Save SSH attribution,
three monitored nodes, ADFA, Falco comparisons and AKS for separate milestones.

## Claims supported by the first finished version

“I built an eBPF-to-Kafka prototype that matches TCP connections across two Linux
VMs. Its rules have fixture tests, and I measured kernel reservation failures,
downstream sequence gaps and latency under a documented workload.”

Use that wording only after those milestones work. Today the accurate claim is
that the non-ML MVP code is implemented and fixture-tested, while live kernel,
Docker and multi-VM verification remains pending. Do
not add figures, product comparisons or claims about hiring statistics without
your own evidence. A short benign run measures observed alert frequency for
that workload, not a general real-world false-positive rate.
