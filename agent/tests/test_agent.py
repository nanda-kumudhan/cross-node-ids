import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
BINARY = ROOT / 'agent/bin/ids-agent'


class AgentTests(unittest.TestCase):
    def test_serialization_sequences_and_clean_closing_watermark(self):
        result = subprocess.run([BINARY, '--self-test', '--node', 'quoted-"node'], text=True, capture_output=True, check=True)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        schema = json.loads((ROOT / 'schemas/event.schema.json').read_text())
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        for event in events:
            validator.validate(event)
            self.assertTrue(event['synthetic'])
            self.assertEqual(event['node'], 'quoted-"node')
            self.assertNotIn('argv', event)
        self.assertEqual([event['seq'] for event in events], list(range(1, 6)))
        self.assertEqual(events[1]['executable'], '/tmp/quoted-"name\\control\n')
        self.assertTrue(events[-1]['final'])
        self.assertEqual(events[-1]['high_watermark'], 5)
        self.assertEqual(events[2]['src_port'], events[3]['src_port'])

    def test_append_preserves_boot_and_changes_run_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'events.jsonl'
            for _ in range(2):
                subprocess.run([BINARY, '--self-test', '--node', 'test', '--output', output], check=True)
            events = [json.loads(line) for line in output.read_text().splitlines()]
            self.assertEqual(events[0]['boot_id'], events[5]['boot_id'])
            self.assertNotEqual(events[0]['agent_run_id'], events[5]['agent_run_id'])
            self.assertEqual(events[0]['seq'], events[5]['seq'])

    def test_invalid_ring_size_and_output_failure_are_errors(self):
        for arguments in (['--ring-bytes', '12345'], ['--output', '/dev/full']):
            result = subprocess.run([BINARY, '--self-test', '--node', 'test', *arguments], capture_output=True)
            self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
