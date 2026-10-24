package dev.crossnode.ids;

import java.time.Instant;
import java.util.LinkedHashMap;
import org.apache.kafka.streams.processor.api.ContextualProcessor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

final class TelemetryProcessor extends ContextualProcessor<String, String, String, ObservedEvent> {
    private KeyValueStore<String, String> states;
    private final long lossLookbackMs;
    TelemetryProcessor(long lossLookbackMs) { this.lossLookbackMs = lossLookbackMs; }

    @Override public void init(ProcessorContext<String, ObservedEvent> context) {
        super.init(context);
        states = context.getStateStore("sequence-state");
    }

    @Override public void process(Record<String, String> record) {
        Event event = Event.parse(record.value());
        if (!event.node().equals(record.key())) throw new IllegalArgumentException("events.raw must be keyed by node");
        String stored = states.get(event.scope());
        if (stored == null && states.approximateNumEntries() >= 10000)
            throw new IllegalStateException("Sequence scope cap reached; archive/reset with a new application ID");
        SequenceState state = stored == null ? new SequenceState() : JsonSupport.read(stored, SequenceState.class);
        boolean fresh = state.observe(event);
        states.put(event.scope(), JsonSupport.write(state));
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("node", event.node()); snapshot.put("boot_id", event.bootId()); snapshot.put("agent_run_id", event.runId());
        snapshot.put("timestamp", Instant.ofEpochMilli(context().currentSystemTimeMs()).toString());
        snapshot.put("revision", state.revision); snapshot.put("received", state.received);
        snapshot.put("sequence_gaps", state.finalizedGaps + state.provisionalGaps());
        snapshot.put("provisional_gaps", state.provisionalGaps()); snapshot.put("finalized_gaps", state.finalizedGaps);
        snapshot.put("kernel_drops", state.kernelDrops); snapshot.put("capture_errors", state.captureErrors);
        snapshot.put("late_unreconciled", state.lateUnreconciled); snapshot.put("highest_seq", state.highest);
        snapshot.put("closed", state.closed);
        boolean knownLoss = state.lastLossTime >= 0 && event.time() >= state.lastLossTime
            && event.time() - state.lastLossTime <= lossLookbackMs;
        context().forward(record.withValue(new ObservedEvent(event, fresh, knownLoss, snapshot)));
    }
}
