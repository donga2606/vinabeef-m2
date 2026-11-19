package vn.edu.hcmut.vinabeef.policy;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.hcmut.vinabeef.policy.ClinicalAuthorizationPolicy.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PolicyModelTest {
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");
    private static final CattleContext CA = new CattleContext("CA", "FA", "BA", 7);
    private static final ClinicalRecord RECORD = new ClinicalRecord("H-A", "CA", "FA");

    static Stream<Arguments> invalidModels() {
        return Stream.of(
                Arguments.of("missing subject", (Executable) () -> new Subject(null, List.of())),
                Arguments.of("blank ID", (Executable) () -> new CattleContext(" ", "FA", "BA", 7)),
                Arguments.of("padded ID", (Executable) () -> new CattleContext("CA", " FA", "BA", 7)),
                Arguments.of("missing farm", (Executable) () -> new CattleContext("CA", null, "BA", 7)),
                Arguments.of("missing barn", (Executable) () -> new CattleContext("CA", "FA", null, 7)),
                Arguments.of("negative custody version", (Executable) () -> new CattleContext("CA", "FA", "BA", -1)),
                Arguments.of("missing grants", (Executable) () -> new Subject("actor", null)),
                Arguments.of("null grant", (Executable) () -> { List<Grant> grants = new ArrayList<>(); grants.add(null); new Subject("actor", grants); }),
                Arguments.of("missing grant reference", (Executable) () -> new Grant(null, Role.FM, "FA", Set.of(), GrantStatus.ACTIVE, NOW, null, 1)),
                Arguments.of("missing role", (Executable) () -> new Grant("g", null, "FA", Set.of(), GrantStatus.ACTIVE, NOW, null, 1)),
                Arguments.of("missing grant farm", (Executable) () -> new Grant("g", Role.FM, null, Set.of(), GrantStatus.ACTIVE, NOW, null, 1)),
                Arguments.of("missing barn set", (Executable) () -> new Grant("g", Role.BS, "FA", null, GrantStatus.ACTIVE, NOW, null, 1)),
                Arguments.of("null barn element", (Executable) () -> { Set<String> barns = new HashSet<>(); barns.add(null); new Grant("g", Role.BS, "FA", barns, GrantStatus.ACTIVE, NOW, null, 1); }),
                Arguments.of("blank barn element", (Executable) () -> new Grant("g", Role.BS, "FA", Set.of(""), GrantStatus.ACTIVE, NOW, null, 1)),
                Arguments.of("missing status", (Executable) () -> new Grant("g", Role.FM, "FA", Set.of(), null, NOW, null, 1)),
                Arguments.of("missing validFrom", (Executable) () -> new Grant("g", Role.FM, "FA", Set.of(), GrantStatus.ACTIVE, null, null, 1)),
                Arguments.of("empty interval", (Executable) () -> new Grant("g", Role.FM, "FA", Set.of(), GrantStatus.ACTIVE, NOW, NOW, 1)),
                Arguments.of("reversed interval", (Executable) () -> new Grant("g", Role.FM, "FA", Set.of(), GrantStatus.ACTIVE, NOW, NOW.minusNanos(1), 1)),
                Arguments.of("negative grant version", (Executable) () -> new Grant("g", Role.FM, "FA", Set.of(), GrantStatus.ACTIVE, NOW, null, -1)),
                Arguments.of("missing cattle", (Executable) () -> new CattleResource(null)),
                Arguments.of("missing history record", (Executable) () -> new HistoryRecord(CA, null)),
                Arguments.of("missing accepted receipt record", (Executable) () -> new AcceptedReceipt("r", CA, null)),
                Arguments.of("missing origin", (Executable) () -> new ClinicalRecord("h", "CA", null)),
                Arguments.of("missing record cattle", (Executable) () -> new ClinicalRecord("h", null, "FA")),
                Arguments.of("missing record id", (Executable) () -> new ClinicalRecord(null, "CA", "FA")),
                Arguments.of("history target mismatch", (Executable) () -> new HistoryRecord(new CattleContext("CB", "FA", "BA", 7), RECORD)),
                Arguments.of("receipt target mismatch", (Executable) () -> new AcceptedReceipt("r", new CattleContext("CB", "FA", "BA", 7), RECORD)),
                Arguments.of("missing receipt id", (Executable) () -> new AcceptedReceipt(null, CA, RECORD)),
                Arguments.of("missing target receipt cattle", (Executable) () -> new TargetReceipt("r", null)),
                Arguments.of("blank target receipt id", (Executable) () -> new TargetReceipt("", CA)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidModels")
    void malformedInputFailsClosedAtConstruction(String label, Executable construction) {
        assertThrows(IllegalArgumentException.class, construction);
    }

    @Test void duplicateGrantReferencesAreRejectedRatherThanChoosingAnArbitraryVersion() {
        Grant v1 = new Grant("g", Role.FM, "FA", Set.of(), GrantStatus.ACTIVE, NOW, null, 1);
        Grant v2 = new Grant("g", Role.FM, "FB", Set.of(), GrantStatus.ACTIVE, NOW, null, 2);
        assertThrows(IllegalArgumentException.class, () -> new Subject("actor", List.of(v1, v2)));
    }

    @Test void callerCollectionsCannotMutateAuthorityAfterConstruction() {
        Set<String> barns = new HashSet<>(Set.of("BA"));
        Grant grant = new Grant("g", Role.BS, "FA", barns, GrantStatus.ACTIVE, NOW, null, 1);
        List<Grant> grants = new ArrayList<>(List.of(grant));
        Subject subject = new Subject("actor", grants);
        barns.clear(); barns.add("BB"); grants.clear();
        ClinicalAuthorizationPolicy policy = new ClinicalAuthorizationPolicy();
        assertTrue(policy.evaluate(Action.SUBMIT_HEALTH, subject, new CattleResource(CA), NOW).allowed());
        assertFalse(policy.evaluate(Action.SUBMIT_HEALTH, subject,
                new CattleResource(new CattleContext("CB", "FA", "BB", 7)), NOW).allowed());
        assertThrows(UnsupportedOperationException.class, () -> subject.grants().clear());
        assertThrows(UnsupportedOperationException.class, () -> grant.barnIds().clear());
    }

    @Test void nullEvaluationArgumentsHaveExplicitValidationFailure() {
        ClinicalAuthorizationPolicy policy = new ClinicalAuthorizationPolicy();
        Subject subject = new Subject("actor", List.of());
        ResourceContext resource = new CattleResource(CA);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> policy.evaluate(null, subject, resource, NOW)),
                () -> assertThrows(IllegalArgumentException.class, () -> policy.evaluate(Action.VIEW_CATTLE, null, resource, NOW)),
                () -> assertThrows(IllegalArgumentException.class, () -> policy.evaluate(Action.VIEW_CATTLE, subject, null, NOW)),
                () -> assertThrows(IllegalArgumentException.class, () -> policy.evaluate(Action.VIEW_CATTLE, subject, resource, null)));
    }

    @Test void publicDecisionCannotEncodeFailOpenOrPositiveMatchOnDeny() {
        GrantReference match = new GrantReference("g", 1);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Decision(true, Reason.NO_MATCHING_GRANT, match)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Decision(false, Reason.CURRENT_FARM, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Decision(true, Reason.CURRENT_FARM, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Decision(false, Reason.NO_MATCHING_GRANT, match)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Decision(false, null, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new GrantReference(null, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new GrantReference("g", -1)));
    }
}
