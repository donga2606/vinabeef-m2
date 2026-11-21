package vn.edu.hcmut.vinabeef.health.adapter.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.hcmut.vinabeef.health.adapter.web.HealthController;
import vn.edu.hcmut.vinabeef.health.application.port.in.GetHealthHistoryUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.GetHealthIntentReceiptUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.LookupCattleUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.SubmitHealthIntentUseCase;
import vn.edu.hcmut.vinabeef.health.config.HealthSecurityConfiguration;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;

/**
 * PHU-02: the real filter chain and principal mapping in front of the Health controller.
 * Only the signature check is replaced (mock JwtDecoder), so no Keycloak or database is needed.
 */
@WebMvcTest(controllers = HealthController.class)
@Import(HealthSecurityConfiguration.class)
class HealthApiSecurityWebTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean LookupCattleUseCase lookupCattle;
    @MockitoBean SubmitHealthIntentUseCase submitHealthIntent;
    @MockitoBean GetHealthIntentReceiptUseCase getHealthIntentReceipt;
    @MockitoBean GetHealthHistoryUseCase getHealthHistory;

    @Test
    void requestWithoutTokenIsRejectedBeforeAnyUseCase() throws Exception {
        mockMvc.perform(get("/api/v1/cattle").param("rfid", "RFID-DL-1001")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/health-intents").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(lookupCattle, submitHealthIntent, getHealthIntentReceipt, getHealthHistory);
    }

    @Test
    void tokenThatFailsVerificationIsRejected() throws Exception {
        when(jwtDecoder.decode("forged")).thenThrow(new BadJwtException("signature does not match"));

        mockMvc.perform(get("/api/v1/cattle").param("rfid", "RFID-DL-1001")
                        .header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(lookupCattle);
    }

    @Test
    void verifiedTokenReachesUseCaseWithUsernameAsTrustedPrincipal() throws Exception {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("verified").header("alg", "RS256")
                .issuer("http://localhost:8081/realms/vinabeef")
                .subject("6f1c7a52-0c9e-4b8e-9f43-000000000001")
                .claim("azp", "vinabeef-mobile")
                .claim("preferred_username", "bs.demo")
                .issuedAt(now).expiresAt(now.plusSeconds(300))
                .build();
        when(jwtDecoder.decode("verified")).thenReturn(jwt);
        AtomicReference<String> principal = new AtomicReference<>();
        when(lookupCattle.lookupByRfid("RFID-DL-1001")).thenAnswer(invocation -> {
            principal.set(new SpringSecurityTrustedPrincipalProvider().requireCurrent().subjectId());
            throw HealthException.notFound("cattle");
        });

        mockMvc.perform(get("/api/v1/cattle").param("rfid", "RFID-DL-1001")
                        .header("Authorization", "Bearer verified"))
                .andExpect(status().isNotFound());

        assertEquals("bs.demo", principal.get());
    }
}
