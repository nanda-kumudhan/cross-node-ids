CREATE TABLE alerts (
    id text PRIMARY KEY,
    rule_id text NOT NULL,
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL
);
CREATE INDEX alerts_time_idx ON alerts (occurred_at DESC);
CREATE TABLE loss_snapshots (
    node text NOT NULL,
    boot_id text NOT NULL,
    agent_run_id text NOT NULL,
    observed_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    PRIMARY KEY (node, boot_id, agent_run_id)
);
