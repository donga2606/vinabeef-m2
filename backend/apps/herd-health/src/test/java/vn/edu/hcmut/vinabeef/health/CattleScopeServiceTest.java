package vn.edu.hcmut.vinabeef.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.hcmut.vinabeef.health.adapter.security.ClinicalAuthorizationPolicyAdapter;
import vn.edu.hcmut.vinabeef.health.application.port.out.CattleScopeQueryPort;
import vn.edu.hcmut.vinabeef.health.application.service.CattleScopeService;
import vn.edu.hcmut.vinabeef.health.application.service.HealthAuthorization;
import vn.edu.hcmut.vinabeef.health.domain.CattleScopeModel.CattleInScope;
import vn.edu.hcmut.vinabeef.health.domain.CattleScopeModel.OpenSchedule;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.GrantStatus;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyGrant;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyRole;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicySubject;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.RegistrationStatus;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.TrustedPrincipal;

/**
 * Pure unit test (no Spring, no Docker): the service filters every candidate row with the real
 * access-policy library through the same ports production uses (DIP makes this possible).
 */
class CattleScopeServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T08:00:00Z");
    private static final CattleResource BARN_01 = cattle("10000000-0000-4000-8000-000000000001", "RFID-DL-1001", "BARN-DL-01");
    private static final CattleResource BARN_02 = cattle("10000000-0000-4000-8000-000000000002", "RFID-DL-1002", "BARN-DL-02");
    private static final OpenSchedule DOSE = new OpenSchedule(UUID.fromString("20000000-0000-4000-8000-000000000001"),
            BARN_01.cattleId(), "FMD-BOOSTER", Instant.parse("2026-11-20T08:00:00Z"));

    private final FakeQuery query = new FakeQuery();

    @Test
    void barnScopedVeterinarianSeesOnlyOwnBarnWithOpenDoses() {
        List<CattleInScope> result = service(grant(PolicyRole.BS, Set.of("BARN-DL-01"), GrantStatus.ACTIVE)).listInScope();

        assertEquals(List.of("RFID-DL-1001"), result.stream().map(c -> c.cattle().rfid()).toList());
        assertEquals(List.of(DOSE), result.getFirst().openSchedules());
        assertEquals(List.of(BARN_01.cattleId()), query.scheduleLookups, "schedules only for visible animals");
    }

    @Test
    void farmManagerSeesWholeFarm() {
        List<CattleInScope> result = service(grant(PolicyRole.FM, Set.of(), GrantStatus.ACTIVE)).listInScope();
        assertEquals(2, result.size());
        assertTrue(result.get(1).openSchedules().isEmpty());
    }

    @Test
    void revokedGrantSeesNothing() {
        assertTrue(service(grant(PolicyRole.BS, Set.of("BARN-DL-01"), GrantStatus.REVOKED)).listInScope().isEmpty());
    }

    @Test
    void subjectWithoutGrantsSeesNothingAndScansNothing() {
        assertTrue(service().listInScope().isEmpty());
        assertTrue(query.farmLookups.isEmpty(), "no grant farm -> no database scan");
    }

    @Test
    void grantAuthorityUnavailableFailsClosed() {
        HealthAuthorization authorization = new HealthAuthorization(
                () -> new TrustedPrincipal("bs.demo"),
                subjectId -> { throw new IllegalStateException("IAM down"); },
                new ClinicalAuthorizationPolicyAdapter(), Clock.fixed(NOW, ZoneOffset.UTC));
        HealthException failure = assertThrows(HealthException.class,
                () -> new CattleScopeService(query, authorization).listInScope());
        assertEquals(503, failure.httpStatus());
    }

    private CattleScopeService service(PolicyGrant... grants) {
        HealthAuthorization authorization = new HealthAuthorization(
                () -> new TrustedPrincipal("bs.demo"),
                subjectId -> new PolicySubject(subjectId, List.of(grants)),
                new ClinicalAuthorizationPolicyAdapter(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new CattleScopeService(query, authorization);
    }

    private static PolicyGrant grant(PolicyRole role, Set<String> barns, GrantStatus status) {
        return new PolicyGrant("grant-" + role, role, "FARM-DL", barns, status,
                Instant.parse("2026-01-01T00:00:00Z"), null, 1);
    }

    private static CattleResource cattle(String id, String rfid, String barn) {
        return new CattleResource(UUID.fromString(id), rfid, rfid, RegistrationStatus.ACTIVE,
                "FARM-DL", "Dak Lak", barn, 0, 0);
    }

    private static final class FakeQuery implements CattleScopeQueryPort {
        final List<Set<String>> farmLookups = new ArrayList<>();
        final List<UUID> scheduleLookups = new ArrayList<>();

        @Override
        public List<CattleResource> findActiveCattleInFarms(Set<String> farmIds) {
            farmLookups.add(farmIds);
            return farmIds.contains("FARM-DL") ? List.of(BARN_01, BARN_02) : List.of();
        }

        @Override
        public List<OpenSchedule> findOpenSchedules(Collection<UUID> cattleIds) {
            scheduleLookups.addAll(cattleIds);
            return cattleIds.contains(DOSE.cattleId()) ? List.of(DOSE) : List.of();
        }
    }
}
