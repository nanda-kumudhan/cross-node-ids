# Intended chain behavior with node-B loss

```mermaid
sequenceDiagram
    participant A as Node A
    participant B as Node B kernel / agent
    participant K as Kafka
    participant S as Streams detector
    participant U as Dashboard
    A->>K: Successful connect (tuple, timestamp, identity)
    B->>B: Ring-buffer reservation fails; increment drops
    B->>K: Surviving events plus drop snapshot
    K->>S: Per-node ordered events
    S->>S: Reconcile gaps and retain loss evidence
    S->>S: Match successful TCP tuples and time window
    alt Surviving evidence satisfies rule
        S->>K: Alert with known_loss=true and evidence references
        K->>U: Persisted alert and loss snapshot
    else Required evidence is missing
        S->>K: Loss snapshot; no connection alert
        Note over S,U: Missing evidence does not imply successful detection
    end
```

The two-node connection detector is implemented and fixture-tested; this diagram
is intended live behavior, not a completed multi-VM experiment. Userspace
sequences cannot observe failed kernel reservations. Counter increases can
annotate surviving evidence; later loss does not amend previously emitted alerts.
An absent required accept cannot be reconstructed from a drop counter.
