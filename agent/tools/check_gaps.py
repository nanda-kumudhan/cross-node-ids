#!/usr/bin/env python3
"""Validate and reconcile sequences in an agent JSON-lines file. No kernel access."""
import argparse
import json
import sys
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from loss import account


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    args = parser.parse_args()
    schema = json.loads((ROOT / 'schemas/event.schema.json').read_text())
    validator = Draft202012Validator(schema, format_checker=FormatChecker())

    def records():
        with args.input.open() as source:
            for line_number, line in enumerate(source, 1):
                try:
                    event = json.loads(line)
                    validator.validate(event)
                    yield event
                except Exception as error:
                    raise ValueError(f'Invalid event at line {line_number}: {error}') from error

    print(json.dumps(account(records()), indent=2))


if __name__ == '__main__':
    main()
