import sys
import tempfile
import unittest
from pathlib import Path

import yaml
from jsonschema.exceptions import ValidationError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from validate_rules import UniqueKeyLoader, validate

ROOT = Path(__file__).resolve().parents[2]


class RuleTests(unittest.TestCase):
    def test_repository_rules(self):
        self.assertEqual(validate(ROOT / "rules", ROOT / "schemas/rule.schema.json"), 3)

    def test_duplicate_yaml_keys_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate YAML key"):
            yaml.load("enabled: true\nenabled: false\n", Loader=UniqueKeyLoader)

    def test_unknown_rule_fields_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            rule = (ROOT / "rules/exec-from-tmp.yml").read_text() + "enabeld: true\n"
            (Path(directory) / "exec-from-tmp.yml").write_text(rule)
            with self.assertRaises(ValidationError):
                validate(directory, ROOT / "schemas/rule.schema.json")

    def test_cross_node_reference_must_exist(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "cross-node-chain.yml").write_text((ROOT / "rules/cross-node-chain.yml").read_text())
            with self.assertRaisesRegex(ValueError, "source_rule"):
                validate(directory, ROOT / "schemas/rule.schema.json")


if __name__ == "__main__":
    unittest.main()
