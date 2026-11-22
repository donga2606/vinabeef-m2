package vn.edu.hcmut.vinabeef.health.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.edu.hcmut.vinabeef.health.domain.CattleScopeModel.OpenSchedule;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;

/**
 * Read-only query port for work-session preparation (ISP: kept separate from the transactional
 * HealthRepository used by submission). Farm filtering here is data minimisation only;
 * every returned row is still authorized by the access policy.
 */
public interface CattleScopeQueryPort {
    List<CattleResource> findActiveCattleInFarms(Set<String> farmIds);

    List<OpenSchedule> findOpenSchedules(Collection<UUID> cattleIds);
}
