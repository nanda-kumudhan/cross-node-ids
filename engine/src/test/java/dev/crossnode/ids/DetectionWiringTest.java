package dev.crossnode.ids;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import static org.junit.jupiter.api.Assertions.*;

class DetectionWiringTest {
    @Test void enabledApplicationBuildsTheRealTopologyWithoutConnectingToKafka() {
        new ApplicationContextRunner().withUserConfiguration(IdsApplication.class)
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("ids.detection.enabled=true", "ids.rules-directory=../rules",
                "ids.ingestion.enabled=false", "spring.kafka.streams.auto-startup=false",
                "spring.flyway.enabled=false")
            .run(context -> {
                org.assertj.core.api.Assertions.assertThat(context).hasNotFailed();
                assertEquals(1, context.getBeansOfType(DetectionModule.class).size());
                var factory = context.getBean("&defaultKafkaStreamsBuilder", StreamsBuilderFactoryBean.class);
                assertTrue(factory.getObject().build().describe().toString().contains("sequence-state"));
                assertEquals("DOWN", context.getBean(DetectionHealth.class).health().getStatus().getCode());
            });
    }
}
