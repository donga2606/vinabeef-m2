package vn.edu.hcmut.vinabeef.policy;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.hcmut.vinabeef.policy.ClinicalAuthorizationPolicy.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClinicalAuthorizationPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");
    private static final CattleContext CA = new CattleContext("CA", "FA", "BA", 7);
    private static final CattleContext CB = new CattleContext("CB", "FA", "BB", 7);
    private static final CattleContext MOVED = new CattleContext("CA", "FB", "BC", 8);
    private static final ClinicalRecord OLD_RECORD = new ClinicalRecord("H-A", "CA", "FA");
    private static final ClinicalRecord NEW_RECORD = new ClinicalRecord("H-B", "CA", "FB");
    private final ClinicalAuthorizationPolicy policy = new ClinicalAuthorizationPolicy();

    private static Grant grant(String ref, Role role, String farm, Set<String> barns) {
        return new Grant(ref, role, farm, barns, GrantStatus.ACTIVE, NOW.minusSeconds(60), null, 2);
    }
    private static Subject subject(Grant... grants) { return new Subject("actor", List.of(grants)); }
    private static Grant bs() { return grant("bs-a", Role.BS, "FA", Set.of("BA")); }
    private static Grant fm() { return grant("fm-a", Role.FM, "FA", Set.of()); }

    static Stream<Arguments> currentScopeCases() {
        return Stream.of(
                Arguments.of("M01 BS assigned view", Action.VIEW_CATTLE, bs(), CA, true),
                Arguments.of("M02 BS wrong barn view", Action.VIEW_CATTLE, bs(), CB, false),
                Arguments.of("M03 FM current farm view", Action.VIEW_CATTLE, fm(), CB, true),
                Arguments.of("M04 FM wrong farm view", Action.VIEW_CATTLE, fm(), MOVED, false),
                Arguments.of("M05 BS assigned submit", Action.SUBMIT_HEALTH, bs(), CA, true),
                Arguments.of("M07 BS wrong barn submit", Action.SUBMIT_HEALTH, bs(), CB, false),
                Arguments.of("M06 FM current farm submit", Action.SUBMIT_HEALTH, fm(), CB, true),
                Arguments.of("M08 FM wrong farm submit", Action.SUBMIT_HEALTH, fm(), MOVED, false),
                Arguments.of("M09 SA submit", Action.SUBMIT_HEALTH, grant("sa", Role.SA, "FA", Set.of("BA")), CA, false),
                Arguments.of("M10 EM submit", Action.SUBMIT_HEALTH, grant("em", Role.EM, "FA", Set.of("BA")), CA, false),
                Arguments.of("M16 destination FM submit", Action.SUBMIT_HEALTH, grant("fm-b", Role.FM, "FB", Set.of()), MOVED, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentScopeCases")
    void currentScope(String label, Action action, Grant grant, CattleContext cattle, boolean allowed) {
        Decision decision = policy.evaluate(action, subject(grant), new CattleResource(cattle), NOW);
        assertEquals(allowed, decision.allowed());
        assertEquals(allowed ? new GrantReference(grant.reference(), 2) : null, decision.matchedGrant());
        assertEquals(allowed ? (grant.role() == Role.BS ? Reason.CURRENT_BARN : Reason.CURRENT_FARM)
                : Reason.NO_MATCHING_GRANT, decision.reason());
    }

    @Test void barnIdentifierAloneCannotCrossFarm() {
        CattleContext otherFarmSameBarnId = new CattleContext("CC", "FB", "BA", 8);
        assertFalse(policy.evaluate(Action.VIEW_CATTLE, subject(bs()),
                new CattleResource(otherFarmSameBarnId), NOW).allowed());
    }

    @Test void cannotMixClinicalRoleWithAnotherGrantsScope() {
        Subject mixed = subject(bs(), grant("technical", Role.SA, "FA", Set.of("BB")));
        assertFalse(policy.evaluate(Action.SUBMIT_HEALTH, mixed, new CattleResource(CB), NOW).allowed());
        Subject farms = subject(fm(), grant("technical", Role.EM, "FB", Set.of("BC")));
        assertFalse(policy.evaluate(Action.SUBMIT_HEALTH, farms, new CattleResource(MOVED), NOW).allowed());
    }

    @Test void emptyBarnsNeverMeanAllBarnsForBs() {
        assertFalse(policy.evaluate(Action.VIEW_CATTLE,
                subject(grant("empty", Role.BS, "FA", Set.of())), new CattleResource(CA), NOW).allowed());
    }

    @Test void independentClinicalGrantBesideTechnicalGrantsAllows() {
        Subject mixed = subject(grant("a-sa", Role.SA, "FA", Set.of("BA")),
                grant("b-em", Role.EM, "FA", Set.of("BA")), bs());
        Decision decision = policy.evaluate(Action.SUBMIT_HEALTH, mixed, new CattleResource(CA), NOW);
        assertEquals(new Decision(true, Reason.CURRENT_BARN, new GrantReference("bs-a", 2)), decision);
    }

    @Test void revokedAndExpiredGrantsNeitherVetoValidGrantNorSupplyScope() {
        Grant revoked = new Grant("a-revoked", Role.BS, "FA", Set.of("BB"), GrantStatus.REVOKED,
                NOW.minusSeconds(60), null, 4);
        Grant expired = new Grant("b-expired", Role.FM, "FB", Set.of(), GrantStatus.ACTIVE,
                NOW.minusSeconds(60), NOW, 5);
        Subject mixed = subject(revoked, expired, bs());
        assertEquals(new GrantReference("bs-a", 2), policy.evaluate(Action.SUBMIT_HEALTH, mixed,
                new CattleResource(CA), NOW).matchedGrant());
        assertFalse(policy.evaluate(Action.SUBMIT_HEALTH, mixed, new CattleResource(CB), NOW).allowed());
        assertFalse(policy.evaluate(Action.SUBMIT_HEALTH, mixed, new CattleResource(MOVED), NOW).allowed());
    }

    static Stream<Arguments> effectiveCases() {
        return Stream.of(
                Arguments.of("before validFrom", GrantStatus.ACTIVE, NOW.plusNanos(1), null, false),
                Arguments.of("at validFrom", GrantStatus.ACTIVE, NOW, NOW.plusSeconds(1), true),
                Arguments.of("before validUntil", GrantStatus.ACTIVE, NOW.minusSeconds(1), NOW.plusNanos(1), true),
                Arguments.of("at validUntil", GrantStatus.ACTIVE, NOW.minusSeconds(1), NOW, false),
                Arguments.of("after validUntil", GrantStatus.ACTIVE, NOW.minusSeconds(2), NOW.minusNanos(1), false),
                Arguments.of("no fixed expiry", GrantStatus.ACTIVE, NOW.minusSeconds(1), null, true),
                Arguments.of("revoked", GrantStatus.REVOKED, NOW.minusSeconds(1), null, false),
                Arguments.of("disabled", GrantStatus.DISABLED, NOW.minusSeconds(1), null, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("effectiveCases")
    void grantEffectiveInterval(String label, GrantStatus status, Instant from, Instant until, boolean allowed) {
        Grant grant = new Grant("g", Role.FM, "FA", Set.of(), status, from, until, 10);
        assertEquals(allowed, policy.evaluate(Action.SUBMIT_HEALTH, subject(grant), new CattleResource(CA), NOW).allowed());
    }

    @Test void serverDecisionTimeControlsPermission() {
        Grant grant = new Grant("g", Role.FM, "FA", Set.of(), GrantStatus.ACTIVE,
                NOW.minusSeconds(10), NOW.plusSeconds(10), 10);
        assertTrue(policy.evaluate(Action.SUBMIT_HEALTH, subject(grant), new CattleResource(CA), NOW).allowed());
        assertFalse(policy.evaluate(Action.SUBMIT_HEALTH, subject(grant), new CattleResource(CA), NOW.plusSeconds(10)).allowed());
    }

    @Test void matchedGrantSelectionIsStableAcrossInputOrder() {
        Grant z = grant("z", Role.BS, "FA", Set.of("BA"));
        Grant a = grant("a", Role.FM, "FA", Set.of());
        Decision expected = new Decision(true, Reason.CURRENT_FARM, new GrantReference("a", 2));
        assertEquals(expected, policy.evaluate(Action.VIEW_CATTLE, subject(z, a), new CattleResource(CA), NOW));
        assertEquals(expected, policy.evaluate(Action.VIEW_CATTLE, subject(a, z), new CattleResource(CA), NOW));
    }

    @Test void currentFarmReasonTakesPrecedenceWithinSameGrant() {
        assertEquals(Reason.CURRENT_FARM, policy.evaluate(Action.READ_HISTORY, subject(fm()),
                new HistoryRecord(CA, OLD_RECORD), NOW).reason());
    }

    @Test void fmOwnOriginIsPerRecordAndDoesNotGrantProfileOrNewWrite() {
        Subject owner = subject(fm());
        assertEquals(Reason.OWN_ORIGIN_RECORD, policy.evaluate(Action.READ_HISTORY, owner,
                new HistoryRecord(MOVED, OLD_RECORD), NOW).reason());
        assertFalse(policy.evaluate(Action.READ_HISTORY, owner, new HistoryRecord(MOVED, NEW_RECORD), NOW).allowed());
        assertFalse(policy.evaluate(Action.VIEW_CATTLE, owner, new CattleResource(MOVED), NOW).allowed());
        assertFalse(policy.evaluate(Action.SUBMIT_HEALTH, owner, new CattleResource(MOVED), NOW).allowed());
    }

    @Test void bsHasNoOwnOriginExtension() {
        assertFalse(policy.evaluate(Action.READ_HISTORY, subject(bs()), new HistoryRecord(MOVED, OLD_RECORD), NOW).allowed());
        assertFalse(policy.evaluate(Action.READ_RECEIPT, subject(bs()),
                new AcceptedReceipt("receipt", MOVED, OLD_RECORD), NOW).allowed());
    }

    @Test void acceptedReceiptAndTargetOnlyReceiptHaveDifferentEntitlements() {
        Subject owner = subject(fm());
        assertEquals(Reason.OWN_ORIGIN_RECORD, policy.evaluate(Action.READ_RECEIPT, owner,
                new AcceptedReceipt("accepted", MOVED, OLD_RECORD), NOW).reason());
        assertFalse(policy.evaluate(Action.READ_RECEIPT, owner, new AcceptedReceipt("new", MOVED, NEW_RECORD), NOW).allowed());
        assertFalse(policy.evaluate(Action.READ_RECEIPT, owner, new TargetReceipt("pending", MOVED), NOW).allowed());
        assertFalse(policy.evaluate(Action.READ_RECEIPT, owner, new TargetReceipt("failed", MOVED), NOW).allowed());
        assertTrue(policy.evaluate(Action.READ_RECEIPT, subject(grant("destination", Role.FM, "FB", Set.of())),
                new TargetReceipt("pending", MOVED), NOW).allowed());
    }

    @Test void currentBarnAuthorizesBothReceiptFormsButWrongBarnDenies() {
        Subject worker = subject(bs());
        assertTrue(policy.evaluate(Action.READ_RECEIPT, worker, new AcceptedReceipt("accepted", CA, OLD_RECORD), NOW).allowed());
        assertTrue(policy.evaluate(Action.READ_RECEIPT, worker, new TargetReceipt("pending", CA), NOW).allowed());
        ClinicalRecord other = new ClinicalRecord("h-cb", "CB", "FA");
        assertFalse(policy.evaluate(Action.READ_RECEIPT, worker, new AcceptedReceipt("other", CB, other), NOW).allowed());
        assertFalse(policy.evaluate(Action.READ_RECEIPT, worker, new TargetReceipt("failed", CB), NOW).allowed());
    }

    @Test void currentReadProjectionDoesNotDependOnLifecycle() {
        // Health supplies this same permission context for known active/deactivated cattle.
        // This verifies only scope, not tag resolution, lifecycle state or returned content.
        Subject allowed = subject(bs());
        Subject wrongBarn = subject(grant("other", Role.BS, "FA", Set.of("BB")));
        for (Subject reader : List.of(allowed, wrongBarn)) {
            assertEquals(reader == allowed, policy.evaluate(Action.VIEW_CATTLE, reader, new CattleResource(CA), NOW).allowed());
            assertEquals(reader == allowed, policy.evaluate(Action.READ_HISTORY, reader, new HistoryRecord(CA, OLD_RECORD), NOW).allowed());
        }
    }

    @Test void currentPermissionProjectionLeavesPayloadAndExpectedVersionToHealth() {
        // No payload/expectedVersion is an API input. M21a/M26a have this allow projection.
        CattleContext latest = new CattleContext("CA", "FA", "BA", 8);
        assertTrue(policy.evaluate(Action.SUBMIT_HEALTH, subject(bs()), new CattleResource(latest), NOW).allowed());
        // M26c current authorized receipt remains readable at latest canonical version.
        assertTrue(policy.evaluate(Action.READ_RECEIPT, subject(bs()), new AcceptedReceipt("committed", latest, OLD_RECORD), NOW).allowed());
        assertFalse(policy.evaluate(Action.SUBMIT_HEALTH, subject(bs()), new CattleResource(MOVED), NOW).allowed());
    }

    static Stream<Arguments> technicalReadCases() {
        return Stream.of(Role.SA, Role.EM).flatMap(role -> Stream.of(
                Arguments.of(role, Action.VIEW_CATTLE, new CattleResource(CA)),
                Arguments.of(role, Action.READ_HISTORY, new HistoryRecord(CA, OLD_RECORD)),
                Arguments.of(role, Action.READ_RECEIPT, new AcceptedReceipt("r", CA, OLD_RECORD))));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("technicalReadCases")
    void technicalRolesCannotReadClinicalData(Role role, Action action, ResourceContext context) {
        assertFalse(policy.evaluate(action, subject(grant("tech", role, "FA", Set.of("BA"))), context, NOW).allowed());
    }

    static Stream<Arguments> correctionCases() {
        return Stream.of(
                Arguments.of("C01 origin FM, animal still at origin", fm(), CA, OLD_RECORD, true),
                Arguments.of("C02 origin FM after transfer", fm(), MOVED, OLD_RECORD, true),
                Arguments.of("C03 BS in the current barn", bs(), CA, OLD_RECORD, false),
                Arguments.of("C04 destination FM, record from another farm", grant("fm-b", Role.FM, "FB", Set.of()), MOVED, OLD_RECORD, false),
                Arguments.of("C05 origin FM, record originated elsewhere", fm(), MOVED, NEW_RECORD, false),
                Arguments.of("C06 SA", grant("sa", Role.SA, "FA", Set.of("BA")), CA, OLD_RECORD, false),
                Arguments.of("C07 EM", grant("em", Role.EM, "FA", Set.of("BA")), CA, OLD_RECORD, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("correctionCases")
    void onlyOriginFarmManagerMayApplyCorrection(String label, Grant grant, CattleContext cattle,
                                                 ClinicalRecord record, boolean allowed) {
        Decision decision = policy.evaluate(Action.APPLY_CORRECTION, subject(grant), new HistoryRecord(cattle, record), NOW);
        assertEquals(allowed, decision.allowed());
        assertEquals(allowed ? Reason.ORIGIN_FARM_CORRECTION : Reason.NO_MATCHING_GRANT, decision.reason());
    }

    @Test void noGrantsOrReceiptIdentifierAloneDoesNotAuthorize() {
        assertEquals(new Decision(false, Reason.NO_MATCHING_GRANT, null),
                policy.evaluate(Action.READ_RECEIPT, subject(), new TargetReceipt("known-id", CA), NOW));
    }

    @Test void actionCannotReuseAnEntitlementForAnotherResourceKind() {
        Subject owner = subject(fm());
        for (Action action : Action.values()) {
            List<ResourceContext> incompatible = switch (action) {
                case VIEW_CATTLE, SUBMIT_HEALTH -> List.of(new HistoryRecord(MOVED, OLD_RECORD),
                        new AcceptedReceipt("accepted", MOVED, OLD_RECORD), new TargetReceipt("pending", CA));
                case READ_HISTORY, APPLY_CORRECTION -> List.of(new CattleResource(CA), new AcceptedReceipt("accepted", CA, OLD_RECORD), new TargetReceipt("pending", CA));
                case READ_RECEIPT -> List.of(new CattleResource(CA), new HistoryRecord(MOVED, OLD_RECORD));
            };
            for (ResourceContext context : incompatible) {
                assertEquals(new Decision(false, Reason.ACTION_CONTEXT_MISMATCH, null), policy.evaluate(action, owner, context, NOW));
            }
        }
    }
}
