package dev.crossnode.ids;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ids.ingestion.enabled", havingValue = "true", matchIfMissing = true)
class AlertConsumer {
    private final DashboardStore store;

    AlertConsumer(DashboardStore store) { this.store = store; }

    @KafkaListener(topics = "${ids.topics.alerts}")
    void alert(String payload) throws JsonProcessingException { store.saveAlert(payload); }

    @KafkaListener(topics = "${ids.topics.loss}")
    void loss(String payload) throws JsonProcessingException { store.saveLoss(payload); }
}
