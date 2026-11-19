package vn.edu.hcmut.vinabeef.policy;

import static org.junit.jupiter.api.Assertions.*;
import static vn.edu.hcmut.vinabeef.policy.ClinicalAuthorizationPolicy.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyUsageTest {
    @Test void readOwnOriginAfterTransferExample() {
        // BEGIN README EXAMPLE
        Instant decisionTime = Instant.parse("2026-11-17T08:00:00Z"); // server-owned in production
        Grant grant = new Grant("grant-fm-fa", Role.FM, "FA", Set.of(), GrantStatus.ACTIVE,
                Instant.parse("2026-11-01T00:00:00Z"), null, 3);
        Subject subject = new Subject("verified-subject", List.of(grant));
        CattleContext current = new CattleContext("CA", "FB", "BC", 8);
        ClinicalRecord record = new ClinicalRecord("H-A", "CA", "FA");
        ClinicalAuthorizationPolicy policy = new ClinicalAuthorizationPolicy();
        Decision history = policy.evaluate(Action.READ_HISTORY, subject,
                new HistoryRecord(current, record), decisionTime);
        Decision receipt = policy.evaluate(Action.READ_RECEIPT, subject,
                new AcceptedReceipt("receipt-H-A", current, record), decisionTime);
        Decision newWrite = policy.evaluate(Action.SUBMIT_HEALTH, subject,
                new CattleResource(current), decisionTime);
        Decision pendingReceipt = policy.evaluate(Action.READ_RECEIPT, subject,
                new TargetReceipt("pending-intent", current), decisionTime);
        // END README EXAMPLE
        assertEquals(new Decision(true, Reason.OWN_ORIGIN_RECORD,
                new GrantReference("grant-fm-fa", 3)), history);
        assertEquals(history, receipt);
        assertEquals(new Decision(false, Reason.NO_MATCHING_GRANT, null), newWrite);
        assertEquals(new Decision(false, Reason.NO_MATCHING_GRANT, null), pendingReceipt);
    }
}
