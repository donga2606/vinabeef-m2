package vn.edu.hcmut.vinabeef.health.adapter.security;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.vinabeef.health.application.port.out.AccessPolicyPort;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.AcceptedReceiptContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResourceContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ClinicalRecordRef;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HistoryRecordContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyAction;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyDecision;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyGrant;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicySubject;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ResourceContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.TargetReceiptContext;
import vn.edu.hcmut.vinabeef.policy.ClinicalAuthorizationPolicy;

@Component
public class ClinicalAuthorizationPolicyAdapter implements AccessPolicyPort {
    private final ClinicalAuthorizationPolicy policy = new ClinicalAuthorizationPolicy();

    @Override
    public PolicyDecision evaluate(PolicyAction action, PolicySubject subject,
                                   ResourceContext resource, Instant decisionTime) {
        ClinicalAuthorizationPolicy.Decision decision = policy.evaluate(
                ClinicalAuthorizationPolicy.Action.valueOf(action.name()),
                toPolicySubject(subject), toPolicyResource(resource), decisionTime);
        ClinicalAuthorizationPolicy.GrantReference matched = decision.matchedGrant();
        return new PolicyDecision(decision.allowed(), decision.reason().name(),
                matched == null ? null : matched.reference(), matched == null ? null : matched.version());
    }

    private ClinicalAuthorizationPolicy.Subject toPolicySubject(PolicySubject subject) {
        List<ClinicalAuthorizationPolicy.Grant> grants = subject.grants().stream()
                .map(this::toPolicyGrant).toList();
        return new ClinicalAuthorizationPolicy.Subject(subject.subjectId(), grants);
    }

    private ClinicalAuthorizationPolicy.Grant toPolicyGrant(PolicyGrant grant) {
        return new ClinicalAuthorizationPolicy.Grant(grant.reference(),
                ClinicalAuthorizationPolicy.Role.valueOf(grant.role().name()), grant.farmId(), grant.barnIds(),
                ClinicalAuthorizationPolicy.GrantStatus.valueOf(grant.status().name()),
                grant.validFrom(), grant.validUntil(), grant.version());
    }

    private ClinicalAuthorizationPolicy.ResourceContext toPolicyResource(ResourceContext resource) {
        if (resource instanceof CattleResourceContext cattle) {
            return new ClinicalAuthorizationPolicy.CattleResource(toPolicyCattle(cattle.cattle()));
        }
        if (resource instanceof HistoryRecordContext history) {
            return new ClinicalAuthorizationPolicy.HistoryRecord(toPolicyCattle(history.cattle()),
                    toPolicyRecord(history.record()));
        }
        if (resource instanceof AcceptedReceiptContext receipt) {
            return new ClinicalAuthorizationPolicy.AcceptedReceipt(receipt.receiptId(),
                    toPolicyCattle(receipt.cattle()), toPolicyRecord(receipt.record()));
        }
        if (resource instanceof TargetReceiptContext receipt) {
            return new ClinicalAuthorizationPolicy.TargetReceipt(receipt.receiptId(),
                    toPolicyCattle(receipt.cattle()));
        }
        throw new IllegalArgumentException("Unsupported Health policy resource");
    }

    private ClinicalAuthorizationPolicy.CattleContext toPolicyCattle(CattleContext cattle) {
        return new ClinicalAuthorizationPolicy.CattleContext(cattle.cattleId(), cattle.farmId(),
                cattle.barnId(), cattle.custodyVersion());
    }

    private ClinicalAuthorizationPolicy.ClinicalRecord toPolicyRecord(ClinicalRecordRef record) {
        return new ClinicalAuthorizationPolicy.ClinicalRecord(record.recordId(), record.cattleId(),
                record.recordOriginFarmId());
    }
}
