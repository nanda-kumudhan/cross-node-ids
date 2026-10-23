#!/usr/bin/env python3
"""Exercise Kafka alert/loss ingestion through the UI's reverse proxy."""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

base_url = sys.argv[1]
compose = sys.argv[2:]


def snapshot():
    with urllib.request.urlopen(base_url + "/api/dashboard", timeout=3) as response:
        return json.load(response)


def await_snapshot(predicate, timeout=90):
    deadline = time.monotonic() + timeout
    last_error = "No response"
    while time.monotonic() < deadline:
        try:
            data = snapshot()
            if predicate(data):
                return data
            last_error = repr(data)
        except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as error:
            last_error = str(error)
        time.sleep(1)
    raise AssertionError(f"Dashboard condition not reached: {last_error}")


def publish(topic, records):
    subprocess.run(compose + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
        "--bootstrap-server", "kafka:9092", "--topic", topic],
        input="".join(json.dumps(record) + "\n" for record in records), text=True, check=True, timeout=30)


initial = await_snapshot(lambda data: data["mode"] == "live")
assert initial["alerts"] == [] and initial["loss"] == []
assert initial["detection_enabled"] is False
with urllib.request.urlopen(base_url + "/replay.json", timeout=3) as response:
    assert json.load(response)["mode"] == "replay"
alert = {"id": "integration-1", "title": "Synthetic integration alert", "rule_id": "exec-from-tmp",
    "timestamp": "2026-01-01T00:00:00Z", "nodes": ["integration-node"], "severity": "medium"}
publish("alerts", [alert, alert])
loss = {"node": "integration-node", "boot_id": "boot-1", "agent_run_id": "run-1",
    "timestamp": "2026-01-01T00:00:02Z", "received": 10, "sequence_gaps": 2, "kernel_drops": 1}
older = dict(loss, timestamp="2026-01-01T00:00:01Z", received=1)
other_run = dict(loss, agent_run_id="run-2", received=3)
publish("metrics.loss", [loss, older, other_run])
data = await_snapshot(lambda value: len(value["alerts"]) == 1 and len(value["loss"]) == 2)
assert data["alerts"][0]["id"] == "integration-1"
first_run = next(row for row in data["loss"] if row["agent_run_id"] == "run-1")
assert first_run["received"] == 10, "A stale snapshot replaced a newer snapshot"
assert first_run["sequence_gaps"] == 2 and first_run["kernel_drops"] == 1
print("Kafka → Postgres → API → UI proxy integration passed; replay served; restart scopes preserved.")
