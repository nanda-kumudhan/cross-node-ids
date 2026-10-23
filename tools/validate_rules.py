#!/usr/bin/env python3
"""Validate rule files and cross-rule references."""
import argparse
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator


class UniqueKeyLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node)
        if key in result:
            raise ValueError(f"Duplicate YAML key: {key}")
        result[key] = loader.construct_object(value_node)
    return result


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def validate(directory, schema_path):
    schema = json.loads(Path(schema_path).read_text())
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    rules = {}
    paths = sorted(Path(directory).glob("*.yml"))
    if not paths:
        raise ValueError("No rule files found")
    for path in paths:
        rule = yaml.load(path.read_text(), Loader=UniqueKeyLoader)
        validator.validate(rule)
        if path.stem != rule["id"]:
            raise ValueError(f"{path}: filename must match rule id")
        if rule["id"] in rules:
            raise ValueError(f"Duplicate rule id: {rule['id']}")
        rules[rule["id"]] = rule
    for rule in rules.values():
        if rule["kind"] == "cross_node":
            source = rules.get(rule["spec"]["source_rule"])
            if source is None or source["kind"] != "sequence":
                raise ValueError(f"{rule['id']}: source_rule must reference a sequence rule")
    return len(rules)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rules", default="rules")
    parser.add_argument("--schema", default="schemas/rule.schema.json")
    args = parser.parse_args()
    print(f"Validated {validate(args.rules, args.schema)} rule definitions (schema only).")
