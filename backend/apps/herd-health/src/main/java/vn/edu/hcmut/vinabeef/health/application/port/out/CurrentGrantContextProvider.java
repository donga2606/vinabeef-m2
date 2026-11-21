package vn.edu.hcmut.vinabeef.health.application.port.out;

import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicySubject;

public interface CurrentGrantContextProvider {
    PolicySubject loadCurrent(String verifiedSubjectId);
}
