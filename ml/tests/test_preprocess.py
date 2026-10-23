import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from preprocess import prepare


class PreprocessingTests(unittest.TestCase):
    def make_splits(self, root):
        paths = [root / name for name in ("train", "validation", "attack")]
        for path, trace in zip(paths, ("1 2 3 4", "1 2 8 9", "7 7 7 7")):
            path.mkdir()
            (path / "trace.txt").write_text(trace)
        return paths

    def test_vocabulary_is_fit_on_training_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prepare(*self.make_splits(root), root / "out", n=2)
            self.assertEqual(json.loads((root / "out/vocabulary.json").read_text()), ["1 2", "2 3", "3 4"])
            self.assertEqual(json.loads((root / "out/attack.novelty.json").read_text()), [1.0])

    def test_rejects_split_leakage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = self.make_splits(root)
            (paths[2] / "copy.txt").write_text("1 2 3 4")
            with self.assertRaisesRegex(ValueError, "overlaps splits"):
                prepare(*paths, root / "out")


if __name__ == "__main__":
    unittest.main()
