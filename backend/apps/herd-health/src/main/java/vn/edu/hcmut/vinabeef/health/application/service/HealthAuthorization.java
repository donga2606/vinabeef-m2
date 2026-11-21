package vn.edu.hcmut.vinabeef.health.application.service;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import vn.edu.hcmut.vinabeef.health.application.port.out.AccessPolicyPort;
import vn.edu.hcmut.vinabeef.health.application.port.out.CurrentGrantContextProvider;
import vn.edu.hcmut.vinabeef.health.application.port.out.TrustedPrincipalProvider;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyAction;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyDecision;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicySubject;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ResourceContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.TrustedPrincipal;

@Service
public class HealthAuthorization {
    private final TrustedPrincipalProvider principalProvider;
    private final CurrentGrantContextProvider grantProvider;
    private final AccessPolicyPort accessPolicy;
    private final Clock clock;

    public HealthAuthorization(TrustedPrincipalProvider principalProvider,
                              CurrentGrantContextProvider grantProvider,
                              AccessPolicyPort accessPolicy,
                              Clock clock) {
        this.principalProvider = principalProvider;
        this.grantProvider = grantProvider;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    public Session openSession() {
        TrustedPrincipal principal = principalProvider.requireCurrent();
        PolicySubject subject;
        try {
            subject = grantProvider.loadCurrent(principal.subjectId());
        } catch (HealthException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw HealthException.unavailable("GRANT_CONTEXT_UNAVAILABLE");
        }
        if (subject == null || !principal.subjectId().equals(subject.subjectId())) {
            throw HealthException.unavailable("GRANT_CONTEXT_INVALID");
        }
        return new Session(principal, subject, clock.instant());
    }

    public boolean allowed(Session session, PolicyAction action, ResourceContext resource) {
        PolicyDecision decision;
        try {
            decision = accessPolicy.evaluate(action, session.subject(), resource, session.decisionTime());
        } catch (HealthException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw HealthException.unavailable("POLICY_EVALUATION_FAILED");
        }
        if (decision == null) throw HealthException.unavailable("POLICY_DECISION_INVALID");
        return decision.allowed();
    }

    public void requireAllowed(Session session, PolicyAction action, ResourceContext resource) {
        if (!allowed(session, action, resource)) throw HealthException.forbidden();
    }

    public record Session(TrustedPrincipal principal, PolicySubject subject, Instant decisionTime) {}
}
