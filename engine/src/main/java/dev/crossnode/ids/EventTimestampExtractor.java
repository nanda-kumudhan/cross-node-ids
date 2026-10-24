package dev.crossnode.ids;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

public final class EventTimestampExtractor implements TimestampExtractor {
    @Override public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (!(record.value() instanceof String value)) throw new IllegalArgumentException("Expected JSON string event");
        return Event.parse(value).time();
    }
}
