package dev.crossnode.ids;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DashboardStore store = new DashboardStore(jdbc, new ObjectMapper());

    @Test
    void rejectsMalformedAlertBeforeDatabaseWrite() {
        assertThrows(IllegalArgumentException.class, () -> store.saveAlert("{\"id\":\"x\"}"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsNegativeLossCounters() {
        assertThrows(IllegalArgumentException.class, () -> store.saveLoss("""
            {"node":"a","boot_id":"boot-1","agent_run_id":"run-1","timestamp":"2026-01-01T00:00:00Z",
             "received":12,"sequence_gaps":-1,"kernel_drops":0}
            """));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsFractionalCounters() {
        assertThrows(IllegalArgumentException.class, () -> store.saveLoss("""
            {"node":"a","boot_id":"boot-1","agent_run_id":"run-1","timestamp":"2026-01-01T00:00:00Z",
             "received":12.5,"sequence_gaps":0,"kernel_drops":0}
            """));
        verifyNoInteractions(jdbc);
    }
}
