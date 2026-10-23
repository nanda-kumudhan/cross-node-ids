package dev.crossnode.ids;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class DashboardController {
    private final DashboardStore store;
    private final boolean detectionEnabled;

    DashboardController(DashboardStore store, @Value("${ids.detection.enabled:false}") boolean detectionEnabled) {
        this.store = store;
        this.detectionEnabled = detectionEnabled;
    }

    @GetMapping("/dashboard")
    Map<String, Object> dashboard() {
        return Map.of("mode", "live", "generated_at", Instant.now().toString(),
            "detection_enabled", detectionEnabled, "alerts", store.alerts(), "loss", store.loss());
    }
}
