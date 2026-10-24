package dev.crossnode.ids;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class DetectionTopologyTest {
    private TopologyTestDriver driver;
    private TestInputTopic<String, String> input;
    private TestOutputTopic<String, String> alerts, loss;
    static final long BASE = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @BeforeEach void setup() throws Exception {
        StreamsBuilder builder = new StreamsBuilder();
        DetectionTopology.build(builder, DetectionRule.load(Path.of("../rules")), "events.raw", "alerts", "metrics.loss");
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        properties.put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("ids-topology-").toString());
        driver = new TopologyTestDriver(builder.build(), properties, Instant.ofEpochMilli(BASE));
        input = driver.createInputTopic("events.raw", new StringSerializer(), new StringSerializer());
        alerts = driver.createOutputTopic("alerts", new StringDeserializer(), new StringDeserializer());
        loss = driver.createOutputTopic("metrics.loss", new StringDeserializer(), new StringDeserializer());
    }
    @AfterEach void close() { if (driver != null) driver.close(); }

    static String event(String node, String type, long sequence, long offset) {
        var value = new LinkedHashMap<String, Object>();
        value.put("schema_version", 1); value.put("node", node); value.put("boot_id", "boot-" + node);
        value.put("agent_run_id", "run-1"); value.put("seq", sequence);
        value.put("timestamp", Instant.ofEpochMilli(BASE + offset).toString()); value.put("type", type);
        value.put("pid", 100); value.put("process_start_ns", 123456L);
        if (type.equals("exec")) value.put("executable", "/tmp/test");
        else if (type.equals("heartbeat")) { value.put("high_watermark", sequence); value.put("final", false); }
        else {
            value.put("protocol", "tcp"); value.put("address_family", "ipv4"); value.put("success", true);
            value.put("src_ip", "10.0.0.1"); value.put("src_port", 40000);
            value.put("dst_ip", "10.0.0.2"); value.put("dst_port", 2222);
        }
        return JsonSupport.write(value);
    }
    static String change(String event, String field, Object value) {
        Map<String, Object> changed = JsonSupport.read(event, Map.class);
        changed.put(field, value);
        return JsonSupport.write(changed);
    }
    void send(String node, String event) { input.pipeInput(node, event, BASE + 999999); } // Deliberately wrong broker time.
    JsonNode lastLoss() throws Exception {
        var values = loss.readValuesToList();
        return JsonSupport.MAPPER.readTree(values.get(values.size() - 1));
    }
    @Test void fixtureProducesOneConnectionAlertWithStableEvidence() throws Exception {
        for (String line : Files.readAllLines(Path.of("../rules/fixtures/connection.jsonl"))) send(Event.parse(line).node(), line);
        var output = alerts.readKeyValue();
        JsonNode value = JsonSupport.MAPPER.readTree(output.value);
        assertEquals("cross-node-connection", value.path("rule_id").asText());
        assertEquals(value.path("id").asText(), output.key);
        assertEquals(2, value.path("evidence").size());
        assertEquals("a", value.path("nodes").get(0).asText());
        assertFalse(value.path("known_loss").asBoolean());
        assertTrue(alerts.isEmpty());
    }
    @Test void reversedArrivalStillJoins() {
        send("b", event("b", "accept", 1, 20)); send("a", event("a", "connect", 1, 0));
        assertEquals(1, alerts.getQueueSize());
    }
    @Test void mismatchedTupleAndSameNodeDoNotJoin() {
        send("a", event("a", "connect", 1, 0));
        send("b", change(event("b", "accept", 1, 10), "src_port", 40001));
        send("a", event("a", "accept", 2, 20));
        assertTrue(alerts.isEmpty());
    }
    @Test void failedConnectDoesNotJoin() {
        send("a", change(event("a", "connect", 1, 0), "success", false));
        send("b", event("b", "accept", 1, 10)); assertTrue(alerts.isEmpty());
    }
    @Test void inclusiveWindowUsesEventTimeInsteadOfBrokerTime() {
        send("a", event("a", "connect", 1, 0)); send("b", event("b", "accept", 1, 5000));
        assertEquals(1, alerts.getQueueSize()); alerts.readValue();
        send("c", change(event("c", "connect", 1, 10000), "src_port", 41000));
        send("d", change(event("d", "accept", 1, 15001), "src_port", 41000));
        assertTrue(alerts.isEmpty());
    }
    @Test void duplicatesDoNotReemitAlertsOrIncreaseUniqueCount() throws Exception {
        String first = event("a", "connect", 1, 0), second = event("b", "accept", 1, 10);
        send("a", first); send("b", second); send("a", first); send("b", second);
        assertEquals(1, alerts.getQueueSize()); assertEquals(1, lastLoss().path("received").asLong());
    }
    @Test void lateSequenceReconcilesAndRestartsHaveSeparateScopes() throws Exception {
        send("a", event("a", "heartbeat", 1, 0)); send("a", event("a", "heartbeat", 3, 20));
        assertEquals(1, lastLoss().path("sequence_gaps").asLong());
        send("a", event("a", "heartbeat", 2, 10)); assertEquals(0, lastLoss().path("sequence_gaps").asLong());
        send("a", change(event("a", "heartbeat", 1, 30), "agent_run_id", "run-2"));
        JsonNode snapshot = lastLoss(); assertEquals("run-2", snapshot.path("agent_run_id").asText());
        assertEquals(1, snapshot.path("received").asLong());
    }
    @Test void missingFirstAndTailRecordsNeedSurvivingHighWatermark() throws Exception {
        send("a", event("a", "heartbeat", 3, 20));
        assertEquals(2, lastLoss().path("sequence_gaps").asLong());
        send("a", change(event("a", "heartbeat", 6, 40), "final", true));
        JsonNode snapshot = lastLoss(); assertTrue(snapshot.path("closed").asBoolean());
        assertEquals(4, snapshot.path("sequence_gaps").asLong());
    }
    @Test void knownLossAnnotatesSurvivingPairButMissingAcceptDoesNotBecomeDetection() throws Exception {
        send("a", change(event("a", "connect", 1, 0), "kernel_drops", 2));
        send("b", event("b", "accept", 1, 10));
        assertTrue(JsonSupport.MAPPER.readTree(alerts.readValue()).path("known_loss").asBoolean());
        send("c", change(event("c", "connect", 1, 20), "src_port", 45000));
        send("d", change(event("d", "heartbeat", 3, 30), "kernel_drops", 5));
        assertTrue(alerts.isEmpty());
    }
    @Test void executionRuleRejectsOrdinaryAndLookalikeDirectories() {
        send("a", change(event("a", "exec", 1, 0), "executable", "/usr/bin/true"));
        send("a", change(event("a", "exec", 2, 10), "executable", "/tmpish/program"));
        assertTrue(alerts.isEmpty());
        send("a", event("a", "exec", 3, 20)); assertEquals(1, alerts.getQueueSize());
    }
    @Test void invalidUnkeyedRecordsFailRatherThanDisappear() {
        assertThrows(Exception.class, () -> input.pipeInput(null, event("a", "connect", 1, 0)));
    }
    @Test void persistentSequenceStoreContainsRestartableState() {
        send("a", event("a", "heartbeat", 1, 0)); send("a", event("a", "heartbeat", 3, 20));
        var store = driver.<String, String>getKeyValueStore("sequence-state");
        var restored = JsonSupport.read(store.get(Event.parse(event("a", "heartbeat", 1, 0)).scope()), SequenceState.class);
        assertEquals(1, restored.provisionalGaps());
        assertTrue(restored.observe(Event.parse(event("a", "heartbeat", 2, 10))));
        assertEquals(0, restored.provisionalGaps());
    }
    @Test void eventsBeyondGraceDoNotReviveExpiredConnections() {
        send("a", event("a", "connect", 1, 0));
        send("c", change(event("c", "connect", 1, 100000), "src_port", 45000));
        send("d", change(event("d", "accept", 1, 100010), "src_port", 45000));
        assertEquals(1, alerts.getQueueSize()); alerts.readValue();
        send("b", event("b", "accept", 1, 10));
        assertTrue(alerts.isEmpty());
    }
    @Test void aProvisionalGapDoesNotClaimConfirmedKernelLoss() throws Exception {
        send("a", event("a", "connect", 3, 0));
        send("b", event("b", "accept", 1, 10));
        assertFalse(JsonSupport.MAPPER.readTree(alerts.readValue()).path("known_loss").asBoolean());
    }
}
