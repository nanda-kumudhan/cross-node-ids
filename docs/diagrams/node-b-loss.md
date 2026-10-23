# Intended chain behavior with node-B loss

```mermaid
sequenceDiagram
    participant A as Node A
    participant B as Node B kernel / agent
    participant K as Kafka
    participant S as Future Streams detector
    participant U as Dashboard
    A->>K: Successful connect (tuple, timestamp, identity)
    B->>B: Ring-buffer reservation fails; increment drops
    B->>K: Surviving events plus drop snapshot
    K->>S: Per-node ordered events
    S->>S: Reconcile gaps and retain loss evidence
    S->>S: Match tuples, session ancestry and time window
    alt Surviving evidence satisfies rule
        S->>K: Alert with known_loss=true and evidence references
        K->>U: Persisted alert and loss snapshot
    else Required evidence is missing
        S->>K: Incomplete correlation / telemetry loss status
        Note over S,U: Missing evidence does not imply successful detection
    end
```

The detector is not implemented. Userspace sequences alone cannot observe failed
kernel reservations. Keep kernel drops and downstream gaps separate.
