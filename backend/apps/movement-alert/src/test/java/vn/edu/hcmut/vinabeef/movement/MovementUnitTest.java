package vn.edu.hcmut.vinabeef.movement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import vn.edu.hcmut.vinabeef.movement.application.MovementEvent;
import vn.edu.hcmut.vinabeef.movement.lab.FaultSwitch;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Path EXAMPLES = Path.of(System.getProperty("contracts.dir", "../../../contracts"))
            .resolve("examples/movement-committed");

    @Test
    void contractExampleBindsAndIsValid() throws Exception {
        MovementEvent e = MAPPER.readValue(EXAMPLES.resolve("passage-in.json").toFile(), MovementEvent.class);
        assertDoesNotThrow(e::requireValid);
        assertEquals(1L, e.edgeSeq());
    }

    @Test
    void eventWithoutEdgeSeqIsRejected() throws Exception {
        MovementEvent e = MAPPER.readValue(EXAMPLES.resolve("invalid/missing-edge-seq.json").toFile(), MovementEvent.class);
        assertThrows(IllegalArgumentException.class, e::requireValid);
    }

    @Test
    void faultSwitchFiresExactlyOnceOnNthCommitAndIsDisarmedByDefault() {
        FaultSwitch f = new FaultSwitch();
        assertFalse(f.haltNow());
        f.arm(3);
        assertFalse(f.haltNow());
        assertFalse(f.haltNow());
        assertTrue(f.haltNow());
        assertFalse(f.haltNow());
    }
}
