package vn.edu.hcmut.vinabeef.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.edu.hcmut.vinabeef.health.application.port.out.CurrentGrantContextProvider;
import vn.edu.hcmut.vinabeef.health.application.service.HealthIntentService;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthIntentSubmission;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyGrant;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyRole;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicySubject;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.GrantStatus;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.RecordType;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class HealthIntentIntegrationTest {
    private static final UUID CATTLE_1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID CATTLE_2 = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID SCHEDULE_1 = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7")
            .withDatabaseName("vinabeef")
            .withUsername("health_app")
            .withPassword("health-local")
            .withUrlParam("currentSchema", "health");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.flyway.create-schemas", () -> true);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired HealthIntentService health;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE TABLE health_audit_outbox, source_evidence, operation_receipt, "
                + "schedule_completion, clinical_record, health_intent CASCADE");
        jdbc.update("UPDATE cattle SET resource_version = 0");
        jdbc.update("UPDATE cattle_custody SET farm_id = CASE cattle_id "
                + "WHEN '10000000-0000-4000-8000-000000000003'::uuid THEN 'FARM-GL' ELSE 'FARM-DL' END, "
                + "barn_id = CASE cattle_id "
                + "WHEN '10000000-0000-4000-8000-000000000002'::uuid THEN 'BARN-DL-02' "
                + "WHEN '10000000-0000-4000-8000-000000000003'::uuid THEN 'BARN-GL-01' "
                + "ELSE 'BARN-DL-01' END, custody_version = 0");
        jdbc.update("UPDATE vaccination_schedule SET status = 'SCHEDULED'");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameIntentThreeRetriesCreateOneEffectAndOneEvidence() throws Exception {
        UUID intentId = UUID.randomUUID();
        String request = vaccinationRequest(intentId, 0);

        authenticate();
        perform(post("/api/v1/health-intents").contentType("application/json").content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.replayed").value(false)).andReturn();
        for (int attempt = 0; attempt < 3; attempt++) {
            authenticate();
            perform(post("/api/v1/health-intents").contentType("application/json").content(request))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.replayed").value(true));
        }

        assertEquals(1, count("clinical_record"));
        assertEquals(1, count("operation_receipt"));
        assertEquals(1, count("source_evidence"));
        assertEquals(1, count("health_audit_outbox"));
        assertEquals(1, jdbc.queryForObject("SELECT resource_version FROM cattle WHERE cattle_id = ?", Integer.class, CATTLE_1));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM schedule_completion WHERE schedule_id = ?", Integer.class, SCHEDULE_1));
    }

    @Test
    void sameIdWithDifferentPayloadConflictsWithoutSecondEffect() throws Exception {
        UUID intentId = UUID.randomUUID();
        String first = healthStatusRequest(intentId, 0, "normal");
        String changed = healthStatusRequest(intentId, 0, "fever");
        authenticate();
        perform(post("/api/v1/health-intents").contentType("application/json").content(first))
                .andExpect(status().isCreated());
        authenticate();
        perform(post("/api/v1/health-intents").contentType("application/json").content(changed))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, count("clinical_record"));
        assertEquals(1, count("source_evidence"));
    }

    @Test
    void tenConcurrentRequestsWithOneIntentIdCreateOneEffect() throws Exception {
        UUID intentId = UUID.randomUUID();
        HealthIntentSubmission submission = new HealthIntentSubmission(intentId, CATTLE_1, null,
                RecordType.HEALTH_STATUS, "{\"observation\":\"normal\"}",
                Instant.parse("2026-11-19T08:00:00Z"), "tablet-barn-01", 0, null, null, null);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> {
                    authenticate();
                    try {
                        return health.submit(submission).httpStatus();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }
            List<Future<Integer>> outcomes = executor.invokeAll(calls).stream().toList();
            long created = 0;
            long replayed = 0;
            for (Future<Integer> outcome : outcomes) {
                int responseStatus = outcome.get();
                if (responseStatus == 201) created++;
                if (responseStatus == 200) replayed++;
            }
            assertEquals(1, created);
            assertEquals(9, replayed);
            assertEquals(1, count("clinical_record"));
            assertEquals(1, count("source_evidence"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void evidenceFailureRollsBackIntentReceiptRecordAndVersion() {
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_health_evidence() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'injected evidence failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_health_evidence BEFORE INSERT ON source_evidence "
                + "FOR EACH ROW EXECUTE FUNCTION fail_health_evidence()");
        try {
            UUID intentId = UUID.randomUUID();
            HealthIntentSubmission submission = new HealthIntentSubmission(intentId, CATTLE_1, null,
                    RecordType.HEALTH_STATUS, "{\"observation\":\"normal\"}",
                    Instant.parse("2026-11-19T08:00:00Z"), "tablet-barn-01", 0, null, null, null);
            authenticate();
            DataAccessException failure = assertThrows(DataAccessException.class, () -> health.submit(submission));
            assertTrue(failure.getMostSpecificCause().getMessage().contains("injected evidence failure"));
            assertEquals(0, count("health_intent"));
            assertEquals(0, count("operation_receipt"));
            assertEquals(0, count("clinical_record"));
            assertEquals(0, count("source_evidence"));
            assertEquals(0, count("health_audit_outbox"));
            assertEquals(0, jdbc.queryForObject("SELECT resource_version FROM cattle WHERE cattle_id = ?", Integer.class, CATTLE_1));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_health_evidence ON source_evidence");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_health_evidence()");
        }
    }

    @Test
    void wrongBarnIsForbiddenAndCreatesNoIntent() throws Exception {
        authenticate();
        String request = objectMapper.writeValueAsString(Maps.request(UUID.randomUUID(), CATTLE_2,
                "HEALTH_STATUS", Map.of("observation", "normal"), 0, null, null, null));
        perform(post("/api/v1/health-intents").contentType("application/json").content(request))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertEquals(0, count("health_intent"));
    }

    @Test
    void unverifiedRfidIsNotAutoCreated() throws Exception {
        authenticate();
        perform(get("/api/v1/cattle").param("rfid", "RFID-UNKNOWN-99"))
                .andExpect(status().isNotFound());
        assertEquals(4, count("cattle"));
    }

    @Test
    void inaccessibleAndUnknownReceiptsHaveTheSameNotFoundResponse() throws Exception {
        UUID intentId = UUID.randomUUID();
        authenticate("bs.demo");
        perform(post("/api/v1/health-intents").contentType("application/json")
                        .content(healthStatusRequest(intentId, 0, "normal")))
                .andExpect(status().isCreated());

        authenticate("denied.demo");
        perform(get("/api/v1/health-intents/{intentId}", intentId)).andExpect(status().isNotFound());
        perform(get("/api/v1/health-intents/{intentId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void staleResourceVersionIsDurablyMarkedForReviewWithoutClinicalEffect() throws Exception {
        jdbc.update("UPDATE cattle SET resource_version = 4 WHERE cattle_id = ?", CATTLE_1);
        jdbc.update("UPDATE cattle_custody SET custody_version = 3 WHERE cattle_id = ?", CATTLE_1);
        authenticate();
        perform(post("/api/v1/health-intents").contentType("application/json")
                        .content(healthStatusRequest(UUID.randomUUID(), 0, "normal")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.receipt.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.receipt.reasonCode").value("RESOURCE_VERSION_CHANGED"));
        assertEquals(0, count("clinical_record"));
        assertEquals(1, count("source_evidence"));
        assertEquals(1, count("health_audit_outbox"));
    }

    @Test
    void scheduledDoseCannotCreateTwoEffectiveRecordsAndCorrectionsStayLinked() throws Exception {
        UUID firstIntent = UUID.randomUUID();
        authenticate();
        perform(post("/api/v1/health-intents").contentType("application/json")
                        .content(vaccinationRequest(firstIntent, 0)))
                .andExpect(status().isCreated());

        authenticate();
        perform(post("/api/v1/health-intents").contentType("application/json")
                        .content(vaccinationRequest(UUID.randomUUID(), 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.receipt.reasonCode").value("SCHEDULE_ALREADY_COMPLETED"));
        assertEquals(1, count("clinical_record"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM schedule_completion WHERE schedule_id = ?", Integer.class, SCHEDULE_1));

        UUID originalRecord = jdbc.queryForObject("SELECT record_id FROM clinical_record WHERE intent_id = ?", UUID.class, firstIntent);
        String correction = correctionRequest(UUID.randomUUID(), originalRecord, 1);
        authenticate();
        perform(post("/api/v1/health-intents").contentType("application/json").content(correction))
                .andExpect(status().isCreated());
        assertEquals(2, count("clinical_record"));
        assertEquals(originalRecord, jdbc.queryForObject("SELECT correction_of_record_id FROM clinical_record "
                + "WHERE correction_of_record_id IS NOT NULL", UUID.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM schedule_completion WHERE schedule_id = ?", Integer.class, SCHEDULE_1));
        UUID correctedRecord = jdbc.queryForObject("SELECT record_id FROM clinical_record "
                + "WHERE correction_of_record_id IS NOT NULL", UUID.class);
        assertEquals(correctedRecord, jdbc.queryForObject("SELECT effective_record_id FROM schedule_completion "
                + "WHERE schedule_id = ?", UUID.class, SCHEDULE_1));
    }

    @Test
    void currentFarmManagerCanReadOriginRecordAndReceiptAfterTransferButCannotViewDestinationCattle() throws Exception {
        UUID intentId = UUID.randomUUID();
        authenticate("bs.demo");
        perform(post("/api/v1/health-intents").contentType("application/json")
                        .content(healthStatusRequest(intentId, 0, "normal")))
                .andExpect(status().isCreated());
        UUID cattleId = CATTLE_1;
        jdbc.update("UPDATE cattle_custody SET farm_id = 'FARM-GL', barn_id = 'BARN-GL-01', "
                + "custody_version = 1 WHERE cattle_id = ?", cattleId);
        jdbc.update("UPDATE cattle SET resource_version = 2 WHERE cattle_id = ?", cattleId);

        authenticate("fm.origin");
        perform(get("/api/v1/health-intents/{intentId}", intentId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        perform(get("/api/v1/cattle/{cattleId}/health-history", cattleId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].originFarmId").value("FARM-DL"));
        perform(get("/api/v1/cattle").param("rfid", "RFID-DL-1001"))
                .andExpect(status().isNotFound()); // Outside-scope RFID lookup returns 404 per OpenAPI.
    }

    private String vaccinationRequest(UUID intentId, long expectedVersion) throws Exception {
        return objectMapper.writeValueAsString(Maps.request(intentId, CATTLE_1, "VACCINATION",
                Map.of("vaccineCode", "FMD-BOOSTER", "batchNumber", "BATCH-2026-11"),
                expectedVersion, SCHEDULE_1, null, null));
    }

    private String healthStatusRequest(UUID intentId, long expectedVersion, String observation) throws Exception {
        return objectMapper.writeValueAsString(Maps.request(intentId, CATTLE_1, "HEALTH_STATUS",
                Map.of("observation", observation), expectedVersion, null, null, null));
    }

    private String correctionRequest(UUID intentId, UUID recordId, long expectedVersion) throws Exception {
        return objectMapper.writeValueAsString(Maps.request(intentId, CATTLE_1, "VACCINATION",
                Map.of("vaccineCode", "FMD-BOOSTER", "batchNumber", "BATCH-CORRECTED"),
                expectedVersion, null, recordId, "Corrected batch number after checking the vial."));
    }

    /** Principal chosen by authenticate(); kept per test because the filter chain clears the holder. */
    private Authentication principal;

    /**
     * Requests go through the real security filter chain. The principal chosen with authenticate()
     * is attached to every request: the chain clears the thread-local context after each request,
     * so reading it again would silently fall back to a stale test principal.
     */
    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(principal == null ? request
                : request.with(SecurityMockMvcRequestPostProcessors.authentication(principal)));
    }

    @Test
    void requestWithoutBearerTokenIsUnauthenticatedProblem() throws Exception {
        principal = null;
        SecurityContextHolder.clearContext();
        mockMvc.perform(get("/api/v1/cattle/in-scope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/v1/health-intents").contentType("application/json")
                        .content(healthStatusRequest(UUID.randomUUID(), 0, "normal")))
                .andExpect(status().isUnauthorized());
        assertEquals(0, count("health_intent"));
    }

    @Test
    void cattleInScopeListsOnlyAnimalsTheCallerMayViewWithOpenSchedules() throws Exception {
        authenticate("bs.demo");
        perform(get("/api/v1/cattle/in-scope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].rfid").value("RFID-DL-1001"))
                .andExpect(jsonPath("$.items[0].openSchedules[0].scheduleId").value(SCHEDULE_1.toString()));

        authenticate("fm.origin");
        perform(get("/api/v1/cattle/in-scope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)); // FARM-DL barns 01 + 02, not FARM-GL / UNKNOWN

        authenticate("denied.demo");
        perform(get("/api/v1/cattle/in-scope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void completedScheduleIsNoLongerOpenInScope() throws Exception {
        authenticate();
        perform(post("/api/v1/health-intents").contentType("application/json")
                        .content(vaccinationRequest(UUID.randomUUID(), 0)))
                .andExpect(status().isCreated());
        authenticate();
        perform(get("/api/v1/cattle/in-scope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].resourceVersion").value(1))
                .andExpect(jsonPath("$.items[0].openSchedules.length()").value(0));
    }

    private void authenticate() {
        authenticate("bs.demo");
    }

    private void authenticate(String subjectId) {
        principal = UsernamePasswordAuthenticationToken.authenticated(subjectId, "unused", List.of());
        SecurityContextHolder.getContext().setAuthentication(principal);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @TestConfiguration
    static class GrantFixture {
        @Bean
        @Primary
        CurrentGrantContextProvider currentGrantContextProvider() {
            return subjectId -> {
                if ("denied.demo".equals(subjectId)) return new PolicySubject(subjectId, List.of());
                if ("fm.origin".equals(subjectId)) {
                    return new PolicySubject(subjectId, List.of(new PolicyGrant("grant-fm-farm-dl",
                            PolicyRole.FM, "FARM-DL", Set.of(), GrantStatus.ACTIVE,
                            Instant.parse("2026-01-01T00:00:00Z"), null, 1)));
                }
                return new PolicySubject(subjectId, List.of(new PolicyGrant("grant-bs-farm-dl-barn-01",
                        PolicyRole.BS, "FARM-DL", Set.of("BARN-DL-01"), GrantStatus.ACTIVE,
                        Instant.parse("2026-01-01T00:00:00Z"), null, 1)));
            };
        }
    }

    private static final class Maps {
        private static java.util.Map<String, Object> request(UUID intentId, UUID cattleId, String type,
                java.util.Map<String, Object> payload, long expectedVersion, UUID scheduleId,
                UUID correctionOf, String correctionReason) {
            java.util.Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("intentId", intentId);
            request.put("cattleId", cattleId);
            request.put("type", type);
            request.put("payload", payload);
            request.put("occurredAt", "2026-11-19T08:00:00Z");
            request.put("deviceRef", "tablet-barn-01");
            request.put("expectedVersion", expectedVersion);
            if (scheduleId != null) request.put("scheduleId", scheduleId);
            if (correctionOf != null) {
                request.put("correctionOfRecordId", correctionOf);
                request.put("correctionReason", correctionReason);
            }
            return request;
        }
    }
}
