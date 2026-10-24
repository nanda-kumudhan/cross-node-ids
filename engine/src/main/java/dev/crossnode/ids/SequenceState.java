package dev.crossnode.ids;

import java.util.Map;
import java.util.TreeMap;

/** Bounded sequence reconciliation; old gaps are finalized rather than forgotten. */
public final class SequenceState {
    public long highest, received, finalizedGaps, kernelDrops, captureErrors, revision, lateUnreconciled;
    public long floor = 1;
    public long latestTime, lastLossTime = -1;
    public boolean closed;
    public TreeMap<Long, Long> missing = new TreeMap<>();
    static final long LOOKBACK = 65536;
    static final int MAX_INTERVALS = 4096;

    /** Returns true only for fresh evidence inside the reconciliation horizon. */
    public boolean observe(Event event) {
        if (closed && event.sequence() > highest) throw new IllegalArgumentException("Event follows the final watermark");
        revision++;
        long sequence = event.sequence();
        if (sequence < floor) { lateUnreconciled++; return false; }
        boolean fresh;
        if (sequence > highest) {
            if (sequence - highest > 1) {
                missing.put(highest + 1, sequence - 1);
            }
            highest = sequence;
            fresh = true;
        } else {
            Map.Entry<Long, Long> interval = missing.floorEntry(sequence);
            fresh = interval != null && interval.getValue() >= sequence;
            if (fresh) {
                long first = interval.getKey(), last = interval.getValue();
                missing.remove(first);
                if (first < sequence) missing.put(first, sequence - 1);
                if (sequence < last) missing.put(sequence + 1, last);
            }
        }
        if (!fresh) return false;
        received++;
        long drops = event.body().path("kernel_drops").asLong(0);
        long errors = event.body().path("capture_errors").asLong(0);
        if (drops > kernelDrops || errors > captureErrors) lastLossTime = Math.max(lastLossTime, event.time());
        kernelDrops = Math.max(kernelDrops, drops);
        captureErrors = Math.max(captureErrors, errors);
        latestTime = Math.max(latestTime, event.time());
        closed |= event.type().equals("heartbeat") && event.body().path("final").asBoolean(false);
        advanceFloor(Math.max(floor, highest - LOOKBACK + 1));
        while (missing.size() > MAX_INTERVALS) advanceFloor(missing.firstEntry().getValue() + 1);
        return true;
    }

    private void advanceFloor(long nextFloor) {
        while (!missing.isEmpty() && missing.firstKey() < nextFloor) {
            var interval = missing.pollFirstEntry();
            long end = Math.min(interval.getValue(), nextFloor - 1);
            finalizedGaps += end - interval.getKey() + 1;
            if (interval.getValue() >= nextFloor) missing.put(nextFloor, interval.getValue());
        }
        floor = nextFloor;
    }

    public long provisionalGaps() {
        long count = 0;
        for (var interval : missing.entrySet()) count += interval.getValue() - interval.getKey() + 1;
        return count;
    }
}
