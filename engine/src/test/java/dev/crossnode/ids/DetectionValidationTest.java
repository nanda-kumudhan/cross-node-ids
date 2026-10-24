package dev.crossnode.ids;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DetectionValidationTest {
    @TempDir Path directory;
    @Test void duplicateYamlAndEnabledUnsupportedRulesFailStartup() throws Exception {
        String single = Files.readString(Path.of("../rules/exec-from-tmp.yml"));
        Path rule = directory.resolve("exec-from-tmp.yml");
        Files.writeString(rule, single + "enabled: false\n");
        assertThrows(Exception.class, () -> DetectionRule.load(directory));
        Files.delete(rule);
        Files.writeString(directory.resolve("ssh-onward-hop.yml"), Files.readString(Path.of("../rules/ssh-onward-hop.yml")).replace("enabled: false", "enabled: true"));
        assertThrows(IllegalArgumentException.class, () -> DetectionRule.load(directory));
    }
    @Test void badNetworkFieldsAndNonintegralSequenceFail() {
        String event = DetectionTopologyTest.event("a", "connect", 1, 0);
        assertThrows(IllegalArgumentException.class, () -> Event.parse(DetectionTopologyTest.change(event, "src_ip", "010.0.0.1")));
        assertThrows(IllegalArgumentException.class, () -> Event.parse(DetectionTopologyTest.change(event, "success", "true")));
        assertThrows(IllegalArgumentException.class, () -> Event.parse(DetectionTopologyTest.change(event, "seq", 1.5)));
    }
    @Test void hugeGapsAreIntervalsAndOldGapsAreFinalizedWithoutMemoryExplosion() {
        SequenceState state = new SequenceState();
        assertTrue(state.observe(Event.parse(DetectionTopologyTest.event("a", "heartbeat", 1_000_000, 0))));
        assertEquals(999999, state.finalizedGaps + state.provisionalGaps());
        assertEquals(1, state.missing.size());
        assertFalse(state.observe(Event.parse(DetectionTopologyTest.event("a", "heartbeat", 1, 10))));
        assertEquals(1, state.lateUnreconciled);
    }
}
