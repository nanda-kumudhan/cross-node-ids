#!/usr/bin/env bash
# Run the app in a separate Compose project.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
check_directory=$(mktemp -d)
check_project="ids-check-$$"
export KAFKA_PORT=0 POSTGRES_PORT=0 ENGINE_PORT=0 UI_PORT=0
mkdir "$check_directory/logs"
python3 - "$check_directory" "$check_project" <<'PY'
import json,sys
from pathlib import Path
directory=Path(sys.argv[1])
project=sys.argv[2]
override={"services":{
    "engine":{"image":f"{project}-engine:check"},
    "ui":{"image":f"{project}-ui:check"},
    "fluent-bit":{"volumes":[f"{directory}/logs:/var/log/agent:ro"]}
}}
(directory/'compose.json').write_text(json.dumps(override))
PY
compose=(docker compose --project-name "$check_project" --env-file .env.example --project-directory . -f deploy/compose.dev.yml -f "$check_directory/compose.json" --profile app)
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then "${compose[@]}" logs --tail=100; fi
  "${compose[@]}" down --volumes --remove-orphans
  # Keep logs and config for debugging.
  printf 'Integration artifacts: %s\n' "$check_directory"
  exit "$status"
}
trap cleanup EXIT
"${compose[@]}" up -d --build --wait --wait-timeout 180
address=$("${compose[@]}" port ui 8080)
python3 scripts/check_app.py "http://$address" "${compose[@]}"
