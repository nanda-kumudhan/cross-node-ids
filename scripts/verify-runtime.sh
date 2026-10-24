#!/usr/bin/env bash
# Explicit native check on an isolated Linux VM. Does not install or deploy.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [ "${EUID}" -ne 0 ]; then
  printf 'Run this on a test VM as root: sudo bash scripts/verify-runtime.sh\n' >&2
  exit 1
fi
test -r /sys/kernel/btf/vmlinux
make agent-build
run_directory=$(mktemp -d /tmp/ids-runtime-XXXXXX)
collector_pid=
cleanup() {
  if [ -n "$collector_pid" ] && kill -0 "$collector_pid" 2>/dev/null; then
    kill -TERM "$collector_pid"
    wait "$collector_pid" || true
  fi
}
trap cleanup EXIT
agent/bin/ids-agent --node native-check --output "$run_directory/events.jsonl" >"$run_directory/stdout" 2>"$run_directory/stderr" &
collector_pid=$!
python3 - "$run_directory" "$collector_pid" <<'PY'
import json, os, sys, time
from pathlib import Path
directory = Path(sys.argv[1])
deadline = time.monotonic() + 15
while time.monotonic() < deadline:
    try:
        os.kill(int(sys.argv[2]), 0)
        if any(json.loads(line).get('ready') for line in (directory/'events.jsonl').read_text().splitlines()):
            break
    except FileNotFoundError:
        pass
    except ProcessLookupError:
        raise RuntimeError((directory/'stderr').read_text())
    time.sleep(0.05)
else:
    raise TimeoutError('Agent never became ready')
PY
python3 - <<'PY'
import socket, subprocess, threading
with socket.socket() as server:
    server.settimeout(5)
    server.bind(('127.0.0.1', 0))
    server.listen(1)
    def accept():
        connection, _ = server.accept()
        connection.close()
    thread = threading.Thread(target=accept)
    thread.start()
    with socket.socket() as client:
        client.connect(server.getsockname())
    thread.join(timeout=5)
    if thread.is_alive():
        raise RuntimeError('Listener did not accept')
subprocess.run(['/usr/bin/true'], check=True)
PY
kill -TERM "$collector_pid"
wait "$collector_pid"
collector_pid=
python3 - "$run_directory/events.jsonl" <<'PY'
import json, sys
events = [json.loads(line) for line in open(sys.argv[1])]
assert events[-1].get('final') is True, 'Missing final watermark'
assert [event['seq'] for event in events] == list(range(1, len(events)+1)), 'Local sequence discontinuity'
assert any(event['type'] == 'exec' and event.get('executable') == '/usr/bin/true' for event in events), 'Missing exec'
connects = [event for event in events if event['type'] == 'connect' and event.get('src_ip') == '127.0.0.1']
accepts = [event for event in events if event['type'] == 'accept' and event.get('src_ip') == '127.0.0.1']
key = lambda event: tuple(event[field] for field in ('src_ip','src_port','dst_ip','dst_port'))
assert {key(event) for event in connects} & {key(event) for event in accepts}, 'No matching network tuple'
print('Native exec/connect/accept smoke test passed:', sys.argv[1])
PY
printf 'Artifacts retained in %s. This loopback check is not a two-VM or overhead benchmark.\n' "$run_directory"
