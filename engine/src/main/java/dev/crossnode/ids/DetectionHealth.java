package dev.crossnode.ids;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

@Component("detection")
class DetectionHealth implements HealthIndicator {
    private final boolean enabled;
    private final ObjectProvider<StreamsBuilderFactoryBean> factories;
    DetectionHealth(@Value("${ids.detection.enabled:false}") boolean enabled, ObjectProvider<StreamsBuilderFactoryBean> factories) {
        this.enabled = enabled; this.factories = factories;
    }
    @Override public Health health() {
        if (!enabled) return Health.up().withDetail("enabled", false).build();
        StreamsBuilderFactoryBean factory = factories.getIfAvailable();
        var streams = factory == null ? null : factory.getKafkaStreams();
        if (streams == null) return Health.down().withDetail("state", "not-started").build();
        var state = streams.state();
        return (state.isRunningOrRebalancing() ? Health.up() : Health.down()).withDetail("state", state.name()).build();
    }
}
