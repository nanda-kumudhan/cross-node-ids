#!/usr/bin/env python3
"""Validate a fixture and publish node-keyed events through local Compose Kafka."""
import argparse
import json
import subprocess
import datetime
import uuid
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--validate-only', action='store_true')
    parser.add_argument('--fresh', action='store_true', help='Rebase fixture times to now and use a new synthetic agent run')
    args = parser.parse_args()
    validator = Draft202012Validator(json.loads((ROOT / 'schemas/event.schema.json').read_text()), format_checker=FormatChecker())
    records = []
    values = []
    for line in args.input.read_text().splitlines():
        value = json.loads(line)
        validator.validate(value)
        if '\t' in value['node'] or '\n' in value['node']:
            raise ValueError('Console-producer replay requires node IDs without tabs/newlines')
        values.append(value)
    if args.fresh and values:
        parse = lambda text: datetime.datetime.fromisoformat(text.replace('Z', '+00:00'))
        first = min(parse(value['timestamp']) for value in values)
        now = datetime.datetime.now(datetime.timezone.utc)
        run = 'fixture-' + str(uuid.uuid4())
        for value in values:
            value['timestamp'] = (now + (parse(value['timestamp']) - first)).isoformat()
            value['agent_run_id'] = run
            value['synthetic'] = True
    records = [value['node'] + '\t' + json.dumps(value) + '\n' for value in values]
    if not records:
        raise ValueError('Empty replay')
    if args.validate_only:
        print(f'Validated {len(records)} replay events')
        return
    command = ['docker', 'compose', '--project-directory', '.', '-f', 'deploy/compose.dev.yml',
               'exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-producer.sh',
               '--bootstrap-server', 'kafka:9092', '--topic', 'events.raw',
               '--property', 'parse.key=true', '--property', 'key.separator=\t']
    subprocess.run(command, cwd=ROOT, input=''.join(records), text=True, check=True, timeout=60)
    print(f'Published {len(records)} fixture events; repeated evidence is deduplicated within the accounting horizon.')


if __name__ == '__main__':
    main()
