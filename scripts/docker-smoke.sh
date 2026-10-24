#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
compose=(docker compose --project-directory . -f deploy/compose.dev.yml)
smoke_id="smoke-$(date +%s)-$$-$RANDOM"
output=$(mktemp)
trap 'rm -f -- "$output"' EXIT

"${compose[@]}" exec -T postgres sh -ec 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "SELECT 1"' >/dev/null
python3 - "$smoke_id" <<'PY'
import datetime, json, sys
marker = sys.argv[1]
event = dict(schema_version=1, node='docker-smoke', boot_id='smoke-boot', agent_run_id=marker,
             seq=1, timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(), type='heartbeat',
             pid=0, process_start_ns=0, high_watermark=1, final=True, synthetic=True, smoke_id=marker)
with open('deploy/logs/events.jsonl', 'a') as output:
    output.write(json.dumps(event) + '\n')
PY

# The consumer can time out after reading the marker; matching it is the check.
timeout 30s "${compose[@]}" exec -T kafka \
    /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:9092 --topic events.raw \
    --from-beginning --timeout-ms 15000 >"$output" 2>&1 || true

if grep -Fq -- "$smoke_id" "$output"; then
    printf 'Postgres query and Fluent Bit -> Kafka smoke test passed.\n'
else
    cat "$output" >&2
    printf 'Smoke event did not reach events.raw. Check make logs.\n' >&2
    exit 1
fi
