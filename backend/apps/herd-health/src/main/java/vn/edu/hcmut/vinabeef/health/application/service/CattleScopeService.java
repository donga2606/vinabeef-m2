package vn.edu.hcmut.vinabeef.health.application.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.vinabeef.health.application.port.in.ListCattleInScopeUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.out.CattleScopeQueryPort;
import vn.edu.hcmut.vinabeef.health.domain.CattleScopeModel.CattleInScope;
import vn.edu.hcmut.vinabeef.health.domain.CattleScopeModel.OpenSchedule;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResourceContext;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyAction;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.PolicyGrant;

/**
 * SRP: only answers "which animals may this caller view right now" for work-session preparation.
 * Submission rules stay in HealthIntentService. Each animal is authorized individually with
 * VIEW_CATTLE (same rule as RFID lookup); a collection is never authorized from one row.
 */
@Service
public class CattleScopeService implements ListCattleInScopeUseCase {
    private final CattleScopeQueryPort query;
    private final HealthAuthorization authorization;

    public CattleScopeService(CattleScopeQueryPort query, HealthAuthorization authorization) {
        this.query = query;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    @Override
    public List<CattleInScope> listInScope() {
        HealthAuthorization.Session session = authorization.openSession();
        Set<String> candidateFarms = session.subject().grants().stream()
                .map(PolicyGrant::farmId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (candidateFarms.isEmpty()) return List.of();

        List<CattleResource> visible = query.findActiveCattleInFarms(candidateFarms).stream()
                .filter(cattle -> authorization.allowed(session, PolicyAction.VIEW_CATTLE,
                        new CattleResourceContext(cattle.policyContext())))
                .toList();
        if (visible.isEmpty()) return List.of();

        Map<UUID, List<OpenSchedule>> schedules = query.findOpenSchedules(
                        visible.stream().map(CattleResource::cattleId).toList()).stream()
                .collect(Collectors.groupingBy(OpenSchedule::cattleId));
        return visible.stream()
                .map(cattle -> new CattleInScope(cattle, schedules.getOrDefault(cattle.cattleId(), List.of())))
                .toList();
    }
}
