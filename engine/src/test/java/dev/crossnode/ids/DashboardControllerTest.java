package dev.crossnode.ids;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DashboardControllerTest {
    @Test
    void emptyLiveDashboardDoesNotInventResults() throws Exception {
        DashboardStore store = mock(DashboardStore.class);
        when(store.alerts()).thenReturn(List.of());
        when(store.loss()).thenReturn(List.of());
        MockMvcBuilders.standaloneSetup(new DashboardController(store, false)).build()
            .perform(get("/api/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("live"))
            .andExpect(jsonPath("$.detection_enabled").value(false))
            .andExpect(jsonPath("$.alerts").isEmpty())
            .andExpect(jsonPath("$.loss").isEmpty());
    }
}
