package dev.crossnode.ids;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;

final class JsonSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonSupport() { }

    static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Cannot serialize value", exception); }
    }

    static <T> T read(String value, Class<T> type) {
        try { return MAPPER.readValue(value, type); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid stored value", exception); }
    }

    static <T> Serde<T> serde(Class<T> type) {
        return Serdes.serdeFrom((topic, value) -> value == null ? null : write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            (topic, bytes) -> bytes == null ? null : read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), type));
    }
}
