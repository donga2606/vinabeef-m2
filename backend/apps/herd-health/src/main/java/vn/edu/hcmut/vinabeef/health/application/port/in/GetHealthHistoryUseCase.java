package vn.edu.hcmut.vinabeef.health.application.port.in;

import java.util.UUID;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HistoryPage;

public interface GetHealthHistoryUseCase {
    HistoryPage history(UUID cattleId, Long beforeVersion, int limit);
}
