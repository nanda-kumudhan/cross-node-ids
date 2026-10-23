package dev.crossnode.ids;

import java.util.List;
import org.apache.kafka.streams.StreamsBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;

@Configuration
@EnableKafkaStreams
@ConditionalOnProperty(name = "ids.detection.enabled", havingValue = "true")
class StreamsConfiguration {
    @Bean
    Object detectionTopology(StreamsBuilder builder, List<DetectionModule> modules) {
        if (modules.isEmpty()) {
            throw new IllegalStateException("Detection is enabled but no DetectionModule is installed");
        }
        modules.forEach(module -> module.build(builder));
        return new Object();
    }
}
