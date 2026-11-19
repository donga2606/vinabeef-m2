package vn.edu.hcmut.vinabeef.health.application.port.out;

import vn.edu.hcmut.vinabeef.health.domain.HealthModel.TrustedPrincipal;

public interface TrustedPrincipalProvider {
    TrustedPrincipal requireCurrent();
}
