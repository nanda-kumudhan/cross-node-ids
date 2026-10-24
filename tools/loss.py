"""Independent offline sequence accounting used to audit agent/replay logs."""
from dataclasses import dataclass, field

MAX_SEQUENCE = 2**53 - 1


@dataclass
class SequenceState:
    highest: int = 0
    received: int = 0
    floor: int = 1
    finalized: int = 0
    kernel_drops: int = 0
    capture_errors: int = 0
    late_unreconciled: int = 0
    closed: bool = False
    missing: list = field(default_factory=list)

    def observe(self, event):
        sequence = event['seq']
        if type(sequence) is not int or not 1 <= sequence <= MAX_SEQUENCE:
            raise ValueError('Invalid sequence')
        if self.closed and sequence > self.highest:
            raise ValueError('Event follows the final watermark')
        if sequence < self.floor:
            self.late_unreconciled += 1
            return False
        if sequence > self.highest:
            if sequence > self.highest + 1:
                self.missing.append((self.highest + 1, sequence - 1))
            self.highest = sequence
        else:
            for index, (first, last) in enumerate(self.missing):
                if first <= sequence <= last:
                    replacement = []
                    if first < sequence:
                        replacement.append((first, sequence - 1))
                    if sequence < last:
                        replacement.append((sequence + 1, last))
                    self.missing[index:index + 1] = replacement
                    break
            else:
                return False
        self.received += 1
        for name in ('kernel_drops', 'capture_errors'):
            value = event.get(name, 0)
            if type(value) is not int or not 0 <= value <= MAX_SEQUENCE:
                raise ValueError(f'Invalid {name}')
            setattr(self, name, max(getattr(self, name), value))
        self.closed |= event.get('type') == 'heartbeat' and event.get('final') is True
        self._advance(max(self.floor, self.highest - 65536 + 1))
        while len(self.missing) > 4096:
            self._advance(self.missing[0][1] + 1)
        return True

    def _advance(self, floor):
        remaining = []
        for first, last in self.missing:
            if first < floor:
                self.finalized += min(last, floor - 1) - first + 1
                if last >= floor:
                    remaining.append((floor, last))
            else:
                remaining.append((first, last))
        self.missing = remaining
        self.floor = floor

    def snapshot(self):
        provisional = sum(last - first + 1 for first, last in self.missing)
        return dict(received=self.received, sequence_gaps=self.finalized + provisional,
                    provisional_gaps=provisional, finalized_gaps=self.finalized,
                    kernel_drops=self.kernel_drops, capture_errors=self.capture_errors,
                    highest_seq=self.highest, late_unreconciled=self.late_unreconciled,
                    closed=self.closed)


def account(events):
    states = {}
    for event in events:
        scope = tuple(event[name] for name in ('node', 'boot_id', 'agent_run_id'))
        if scope not in states and len(states) >= 10000:
            raise ValueError('Too many sequence scopes')
        states.setdefault(scope, SequenceState()).observe(event)
    return [dict(zip(('node', 'boot_id', 'agent_run_id'), scope), **state.snapshot())
            for scope, state in sorted(states.items())]
