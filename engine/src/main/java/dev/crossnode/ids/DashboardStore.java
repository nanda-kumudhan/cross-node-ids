package dev.crossnode.ids;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DashboardStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    DashboardStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void saveAlert(String json) throws JsonProcessingException {
        JsonNode alert = mapper.readTree(json);
        String id = requiredText(alert, "id");
        String rule = requiredText(alert, "rule_id");
        Instant at = Instant.parse(requiredText(alert, "timestamp"));
        String severity = requiredText(alert, "severity");
        if (!List.of("low", "medium", "high", "critical").contains(severity)) {
            throw new IllegalArgumentException("Invalid severity");
        }
        requiredText(alert, "title");
        if (!alert.path("nodes").isArray() || alert.path("nodes").isEmpty()) {
            throw new IllegalArgumentException("Alert nodes must be a nonempty array");
        }
        for (JsonNode node : alert.path("nodes")) {
            if (!node.isTextual() || node.asText().isBlank()) {
                throw new IllegalArgumentException("Alert node IDs must be nonempty strings");
            }
        }
        if (alert.has("known_loss") && !alert.path("known_loss").isBoolean()) {
            throw new IllegalArgumentException("known_loss must be boolean");
        }
        jdbc.update("""
            INSERT INTO alerts (id, rule_id, occurred_at, payload) VALUES (?, ?, ?, ?::jsonb)
            ON CONFLICT (id) DO NOTHING
            """, id, rule, java.sql.Timestamp.from(at), json);
    }

    void saveLoss(String json) throws JsonProcessingException {
        JsonNode loss = mapper.readTree(json);
        String node = requiredText(loss, "node");
        String boot = requiredText(loss, "boot_id");
        String run = requiredText(loss, "agent_run_id");
        Instant at = Instant.parse(requiredText(loss, "timestamp"));
        for (String field : List.of("received", "sequence_gaps", "kernel_drops")) {
            if (!loss.path(field).isIntegralNumber() || !loss.path(field).canConvertToLong()
                    || loss.path(field).longValue() < 0 || loss.path(field).longValue() > 9007199254740991L) {
                throw new IllegalArgumentException("Invalid counter: " + field);
            }
        }
        for (String field : List.of("revision", "provisional_gaps", "finalized_gaps", "capture_errors", "late_unreconciled", "highest_seq")) {
            if (loss.has(field) && (!loss.path(field).isIntegralNumber() || !loss.path(field).canConvertToLong()
                    || loss.path(field).longValue() < 0 || loss.path(field).longValue() > 9007199254740991L))
                throw new IllegalArgumentException("Invalid counter: " + field);
        }
        if (loss.has("closed") && !loss.path("closed").isBoolean()) throw new IllegalArgumentException("Invalid closed flag");
        jdbc.update("""
            INSERT INTO loss_snapshots (node, boot_id, agent_run_id, observed_at, payload) VALUES (?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (node, boot_id, agent_run_id) DO UPDATE SET observed_at = EXCLUDED.observed_at,
              payload = EXCLUDED.payload WHERE CASE
                WHEN (loss_snapshots.payload->>'revision') IS NOT NULL AND (EXCLUDED.payload->>'revision') IS NOT NULL
                THEN (loss_snapshots.payload->>'revision')::bigint < (EXCLUDED.payload->>'revision')::bigint
                WHEN (EXCLUDED.payload->>'revision') IS NOT NULL THEN true
                WHEN (loss_snapshots.payload->>'revision') IS NOT NULL THEN false
                ELSE loss_snapshots.observed_at < EXCLUDED.observed_at END
            """, node, boot, run, java.sql.Timestamp.from(at), json);
    }

    List<JsonNode> alerts() {
        return jdbc.query("SELECT payload::text FROM alerts ORDER BY occurred_at DESC, id LIMIT 200",
            (rs, index) -> parseStored(rs.getString(1)));
    }

    List<JsonNode> loss() {
        return jdbc.query("SELECT payload::text FROM loss_snapshots ORDER BY node, boot_id, agent_run_id",
            (rs, index) -> parseStored(rs.getString(1)));
    }

    private JsonNode parseStored(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored payload", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.path(field).isTextual() || node.path(field).asText().isBlank()) {
            throw new IllegalArgumentException("Missing text field: " + field);
        }
        return node.path(field).asText();
    }
}
