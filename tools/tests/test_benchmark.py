import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('benchmark_run', ROOT / 'tools/benchmark/run.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BenchmarkTests(unittest.TestCase):
    def test_percentiles_cover_empty_singleton_and_interpolation(self):
        self.assertIsNone(module.percentile([], 95))
        self.assertEqual(module.percentile([3], 95), 3)
        self.assertEqual(module.percentile([0, 10], 50), 5)
        self.assertEqual(module.percentile([0, 10], 95), 9.5)

    def test_latency_rejects_synthetic_results_and_requires_clock_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'snapshot.json'
            alert = dict(synthetic=True, evidence=[{}], timestamp='2026-01-01T00:00:00Z', detected_at='2026-01-01T00:00:00.020Z')
            path.write_text(json.dumps(dict(alerts=[alert])))
            command = [sys.executable, str(ROOT / 'tools/benchmark/alert_latency.py'), str(path), '--clock-uncertainty-ms', '1']
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            alert['synthetic'] = False
            path.write_text(json.dumps(dict(alerts=[alert])))
            result = subprocess.run(command, capture_output=True, text=True, check=True)
            self.assertEqual(json.loads(result.stdout)['p95_ms'], 20)
            self.assertNotEqual(subprocess.run(command[:-1] + ['nan'], capture_output=True).returncode, 0)
