package dev.crossnode.ids;

import org.apache.kafka.streams.StreamsBuilder;

/** Adds detection logic to the Streams topology. */
public interface DetectionModule {
    void build(StreamsBuilder builder);
}
