package dev.crossnode.ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.Stores;

public final class DetectionTopology {
    private DetectionTopology() { }

    public static void build(StreamsBuilder builder, List<DetectionRule> rules, String events, String alerts, String loss) {
        long lookback = rules.stream().filter(rule -> rule.kind().equals("connection"))
            .mapToLong(rule -> (rule.seconds("within_seconds") + rule.seconds("grace_seconds")) * 1000).max().orElse(15000);
        builder.addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("sequence-state"), Serdes.String(), Serdes.String()));
        KStream<String, ObservedEvent> observed = builder.stream(events,
            Consumed.with(Serdes.String(), Serdes.String()).withTimestampExtractor(new EventTimestampExtractor()))
            .process(() -> new TelemetryProcessor(lookback), Named.as("account-telemetry"), "sequence-state");
        observed.selectKey((key, value) -> value.event().scope())
            .mapValues(value -> JsonSupport.write(value.snapshot())).to(loss, Produced.with(Serdes.String(), Serdes.String()));
        KStream<String, ObservedEvent> fresh = observed.filter((key, value) -> value.fresh());
        for (DetectionRule rule : rules) {
            if (rule.kind().equals("single")) {
                fresh.filter((key, value) -> value.event().type().equals("exec")
                    && rule.prefixes().stream().anyMatch(prefix -> value.event().body().path("executable").asText().startsWith(prefix)))
                    .mapValues(value -> alert(rule, value, null))
                    .selectKey((key, value) -> JsonSupport.read(value, Map.class).get("id").toString())
                    .to(alerts, Produced.with(Serdes.String(), Serdes.String()));
            } else if (rule.kind().equals("connection")) {
                var serde = JsonSupport.serde(ObservedEvent.class);
                KStream<String, ObservedEvent> network = fresh.filter((key, value) -> value.event().network()
                    && value.event().body().path("success").asBoolean());
                var connects = network.filter((key, value) -> value.event().type().equals("connect"))
                    .selectKey((key, value) -> value.event().connectionKey())
                    .repartition(Repartitioned.with(Serdes.String(), serde).withName(rule.id() + "-connect"));
                var accepts = network.filter((key, value) -> value.event().type().equals("accept"))
                    .selectKey((key, value) -> value.event().connectionKey())
                    .repartition(Repartitioned.with(Serdes.String(), serde).withName(rule.id() + "-accept"));
                connects.join(accepts, (left, right) -> left.event().node().equals(right.event().node()) ? null : alert(rule, left, right),
                        JoinWindows.ofTimeDifferenceAndGrace(Duration.ofSeconds(rule.seconds("within_seconds")),
                            Duration.ofSeconds(rule.seconds("grace_seconds"))),
                        StreamJoined.with(Serdes.String(), serde, serde).withName(rule.id()))
                    .filter((key, value) -> value != null)
                    .selectKey((key, value) -> JsonSupport.read(value, Map.class).get("id").toString())
                    .to(alerts, Produced.with(Serdes.String(), Serdes.String()));
            } else throw new IllegalArgumentException("Unsupported enabled rule: " + rule.kind());
        }
    }

    private static String alert(DetectionRule rule, ObservedEvent first, ObservedEvent second) {
        List<Event> evidence = second == null ? List.of(first.event()) : List.of(first.event(), second.event());
        var alert = new LinkedHashMap<String, Object>();
        alert.put("id", digest(rule.id() + "|" + evidence.stream().map(Event::identity).toList()));
        alert.put("rule_id", rule.id()); alert.put("title", rule.title()); alert.put("severity", rule.severity());
        alert.put("description", rule.description());
        alert.put("timestamp", Instant.ofEpochMilli(evidence.stream().mapToLong(Event::time).max().orElseThrow()).toString());
        alert.put("detected_at", Instant.now().toString());
        alert.put("nodes", evidence.stream().map(Event::node).distinct().toList());
        alert.put("known_loss", first.knownLoss() || second != null && second.knownLoss());
        alert.put("loss_annotation", "Kernel/capture failure increase observed near evidence before emission; sequence gaps are reported separately. Later loss does not amend this alert.");
        alert.put("evidence", evidence.stream().map(Event::body).toList());
        alert.put("synthetic", evidence.stream().anyMatch(event -> event.body().path("synthetic").asBoolean(false)));
        return JsonSupport.write(alert);
    }

    private static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
