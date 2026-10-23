import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]


class ContractTests(unittest.TestCase):
    def test_synthetic_replay_matches_public_contract(self):
        schema = json.loads((ROOT / "schemas/dashboard.schema.json").read_text())
        data = json.loads((ROOT / "ui/public/replay.json").read_text())
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(data)
        self.assertEqual(data["mode"], "replay")

    def test_all_schemas_are_valid(self):
        for path in (ROOT / "schemas").glob("*.json"):
            with self.subTest(schema=path.name):
                Draft202012Validator.check_schema(json.loads(path.read_text()))


if __name__ == "__main__":
    unittest.main()
