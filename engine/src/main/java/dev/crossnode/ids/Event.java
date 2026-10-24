package dev.crossnode.ids;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;

/** Validated external event. Raw values remain available for evidence. */
public record Event(String node, String bootId, String runId, long sequence,
                    long time, String type, JsonNode body) {
    private static final Set<String> TYPES = Set.of("exec", "connect", "accept", "heartbeat");

    public static Event parse(String json) {
        try {
            JsonNode value = JsonSupport.MAPPER.readTree(json);
            if (value == null || !value.isObject() || number(value, "schema_version", 1) != 1)
                throw new IllegalArgumentException("Expected schema_version=1 event object");
            String type = text(value, "type");
            if (!TYPES.contains(type)) throw new IllegalArgumentException("Unsupported event type: " + type);
            number(value, "pid", 0);
            number(value, "process_start_ns", 0);
            long at = Instant.parse(text(value, "timestamp")).toEpochMilli();
            if (at < 0) throw new IllegalArgumentException("Pre-epoch event timestamp");
            if (value.has("kernel_drops")) number(value, "kernel_drops", 0);
            if (value.has("capture_errors")) number(value, "capture_errors", 0);
            if (number(value, "seq", 1) > 9007199254740991L) throw new IllegalArgumentException("Sequence exceeds safe metric range");
            for (String field : Set.of("kernel_drops", "capture_errors"))
                if (value.has(field) && number(value, field, 0) > 9007199254740991L) throw new IllegalArgumentException("Counter exceeds safe metric range");
            if (type.equals("heartbeat") && (number(value, "high_watermark", 1) != number(value, "seq", 1)
                    || !value.path("final").isBoolean())) throw new IllegalArgumentException("Invalid heartbeat high-watermark/final flag");
            if (type.equals("exec")) text(value, "executable");
            if (type.equals("connect") || type.equals("accept")) {
                if (!text(value, "protocol").equals("tcp") || !text(value, "address_family").equals("ipv4")
                        || !value.path("success").isBoolean())
                    throw new IllegalArgumentException("Expected explicit IPv4 TCP success flag");
                ipv4(text(value, "src_ip"));
                ipv4(text(value, "dst_ip"));
                for (String field : Set.of("src_port", "dst_port")) {
                    long port = number(value, field, 1);
                    if (port > 65535) throw new IllegalArgumentException("Invalid port");
                }
            }
            return new Event(text(value, "node"), text(value, "boot_id"), text(value, "agent_run_id"),
                number(value, "seq", 1), at, type, value);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid event JSON/timestamp", exception);
        }
    }

    static String text(JsonNode value, String field) {
        if (!value.path(field).isTextual() || value.path(field).asText().isBlank())
            throw new IllegalArgumentException("Missing text: " + field);
        return value.path(field).asText();
    }

    static long number(JsonNode value, String field, long minimum) {
        JsonNode number = value.path(field);
        if (!number.isIntegralNumber() || !number.canConvertToLong() || number.longValue() < minimum)
            throw new IllegalArgumentException("Invalid integer: " + field);
        return number.longValue();
    }

    private static void ipv4(String address) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address");
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
                throw new IllegalArgumentException("IPv4 addresses must be canonical decimal");
        }
    }

    public String scope() { return JsonSupport.write(new String[]{node, bootId, runId}); }
    public String identity() { return JsonSupport.write(new Object[]{node, bootId, runId, sequence}); }
    public boolean network() { return type.equals("connect") || type.equals("accept"); }
    public String connectionKey() {
        return "tcp|ipv4|" + body.path("src_ip").asText() + "|" + body.path("src_port").asInt()
            + "|" + body.path("dst_ip").asText() + "|" + body.path("dst_port").asInt();
    }
}
