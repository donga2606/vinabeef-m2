package vn.edu.hcmut.vinabeef.health.adapter.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.vinabeef.health.application.port.out.TrustedPrincipalProvider;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.TrustedPrincipal;

/** Reads the principal installed by the verified security-http authentication filter. */
@Component
public class SpringSecurityTrustedPrincipalProvider implements TrustedPrincipalProvider {
    @Override
    public TrustedPrincipal requireCurrent() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw HealthException.unauthenticated();
        }
        String subjectId = authentication.getName();
        if (subjectId == null || subjectId.isBlank()) throw HealthException.unauthenticated();
        return new TrustedPrincipal(subjectId);
    }
}
