package vn.edu.hcmut.vinabeef.health.application.port.out;

import java.time.Instant;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyAction;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyDecision;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicySubject;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.ResourceContext;

/** Adapter boundary for Phuc's pure-Java ClinicalAuthorizationPolicy library. */
public interface AccessPolicyPort {
    PolicyDecision evaluate(PolicyAction action, PolicySubject subject, ResourceContext resource, Instant decisionTime);
}
