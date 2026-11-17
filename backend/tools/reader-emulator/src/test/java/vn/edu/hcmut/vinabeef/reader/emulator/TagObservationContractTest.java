package vn.edu.hcmut.vinabeef.reader.emulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks contracts/examples and emulator output against contracts/events/tag-observation.v1.schema.json. */
class TagObservationContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONTRACTS = Path.of(System.getProperty("contracts.dir", "../../../contracts"));
    private static final Path EXAMPLES = CONTRACTS.resolve("examples/reader-observation");
    private static final JsonSchema SCHEMA = loadSchema();

    private static JsonSchema loadSchema() {
        try {
            JsonNode node = MAPPER.readTree(CONTRACTS.resolve("events/tag-observation.v1.schema.json").toFile());
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node, config);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read schema under " + CONTRACTS.toAbsolutePath(), e);
        }
    }

    private static Set<ValidationMessage> validate(JsonNode node) {
        return SCHEMA.validate(node);
    }

    @TestFactory
    Stream<DynamicTest> validExamplesConform() throws IOException {
        return jsonFiles(EXAMPLES).map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> {
            Set<ValidationMessage> errors = validate(MAPPER.readTree(p.toFile()));
            assertTrue(errors.isEmpty(), () -> p + " -> " + errors);
        }));
    }

    @TestFactory
    Stream<DynamicTest> invalidExamplesAreRejected() throws IOException {
        return jsonFiles(EXAMPLES.resolve("invalid")).map(p -> DynamicTest.dynamicTest(p.getFileName().toString(),
                () -> assertFalse(validate(MAPPER.readTree(p.toFile())).isEmpty(), p + " should be rejected")));
    }

    @Test
    void emulatorOutputConformsForBothKinds() {
        for (String kind : new String[] {"GATE", "MOBILE"}) {
            EmulatorOptions o = EmulatorOptions.parse("--kind", kind, "--count", "12", "--dup-every", "4",
                    "--gap-every", "5", "--start", "2026-11-17T02:00:00Z");
            new ObservationGenerator(UUID::randomUUID).generate(o).forEach(obs -> {
                Set<ValidationMessage> errors = validate(obs);
                assertTrue(errors.isEmpty(), () -> obs + " -> " + errors);
            });
        }
    }

    @Test
    void rejectsBadFormats() {
        var obs = MAPPER.createObjectNode()
                .put("schemaVersion", "tag-observation.v1").put("observationId", "not-a-uuid")
                .put("readerId", "GATE-FARM01-A").put("readerKind", "GATE").put("seq", 1)
                .put("tagId", "VN-1").put("observedAt", Instant.EPOCH.toString().replace("T", " "));
        assertFalse(validate(obs).isEmpty());
    }

    private static Stream<Path> jsonFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
        }
    }
}
