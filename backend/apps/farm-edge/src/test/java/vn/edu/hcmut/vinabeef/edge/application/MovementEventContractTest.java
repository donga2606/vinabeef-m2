package vn.edu.hcmut.vinabeef.edge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import vn.edu.hcmut.vinabeef.edge.config.EdgeProperties;
import vn.edu.hcmut.vinabeef.edge.domain.Decision;
import vn.edu.hcmut.vinabeef.edge.domain.Direction;
import vn.edu.hcmut.vinabeef.edge.domain.PassageClassifier;
import vn.edu.hcmut.vinabeef.edge.domain.Presence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The event farm-edge writes to its outbox must match contracts/events/movement-committed.v1.schema.json. */
class MovementEventContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONTRACTS = Path.of(System.getProperty("contracts.dir", "../../../contracts"));

    private static JsonSchema schema() throws IOException {
        JsonNode node = MAPPER.readTree(CONTRACTS.resolve("events/movement-committed.v1.schema.json").toFile());
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(node, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    @Test
    void producedEventConformsEvenWhenSecondsAreZero() throws IOException {
        GateService service = new GateService(null, new PassageClassifier(Duration.ofSeconds(5)), MAPPER,
                new EdgeProperties("FARM-01", Duration.ofSeconds(5), null));
        OffsetDateTime observedAt = OffsetDateTime.of(2026, 11, 17, 9, 0, 0, 0, ZoneOffset.ofHours(7));
        ObservationRequest req = new ObservationRequest("tag-observation.v1", UUID.randomUUID(), "GATE-FARM01-A",
                "GATE", 1L, "VN-0001-000123", observedAt, null, "IN");
        String json = service.movementEvent(UUID.randomUUID(), 1, req,
                new Decision.NewPassage(Direction.IN, Presence.INSIDE, false), OffsetDateTime.of(2026, 11, 17, 2, 0, 0, 0, ZoneOffset.UTC));

        Set<ValidationMessage> errors = schema().validate(MAPPER.readTree(json));
        assertTrue(errors.isEmpty(), () -> json + " -> " + errors);
    }

    @Test
    void examplesValidateAndInvalidOnesAreRejected() throws IOException {
        JsonSchema schema = schema();
        Path dir = CONTRACTS.resolve("examples/movement-committed");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                assertTrue(schema.validate(MAPPER.readTree(p.toFile())).isEmpty(), p.toString());
            }
        }
        try (Stream<Path> files = Files.list(dir.resolve("invalid"))) {
            for (Path p : files.toList()) {
                assertFalse(schema.validate(MAPPER.readTree(p.toFile())).isEmpty(), p + " should be rejected");
            }
        }
    }
}
