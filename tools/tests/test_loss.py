import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from loss import SequenceState, account


class LossTests(unittest.TestCase):
    def test_reconciliation_duplicates_and_nonoverlapping_counters(self):
        state = SequenceState()
        for sequence in (1, 3, 3, 2):
            state.observe(dict(seq=sequence, kernel_drops=4))
        self.assertEqual(state.snapshot()['received'], 3)
        self.assertEqual(state.snapshot()['sequence_gaps'], 0)
        self.assertEqual(state.snapshot()['kernel_drops'], 4)

    def test_restart_scopes_and_lost_tail(self):
        events = [dict(node='a', boot_id='boot', agent_run_id=run, seq=sequence,
                       type='heartbeat', final=final)
                  for run, sequence, final in (('one', 1, False), ('one', 4, True), ('two', 1, False))]
        snapshots = account(events)
        self.assertEqual(len(snapshots), 2)
        self.assertEqual(snapshots[0]['sequence_gaps'], 2)
        self.assertTrue(snapshots[0]['closed'])
        self.assertFalse(snapshots[1]['closed'])

    def test_large_gaps_and_late_records_are_bounded(self):
        state = SequenceState()
        state.observe(dict(seq=1000000))
        self.assertEqual(len(state.missing), 1)
        self.assertEqual(state.snapshot()['sequence_gaps'], 999999)
        self.assertFalse(state.observe(dict(seq=1)))
        self.assertEqual(state.late_unreconciled, 1)
