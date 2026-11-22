package vn.edu.hcmut.vinabeef.health.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;

/** Read model for preparing a mobile work session: animals the caller may view now, plus open doses. */
public final class CattleScopeModel {
    private CattleScopeModel() {}

    /** A scheduled dose that is still SCHEDULED and has no effective clinical record. */
    public record OpenSchedule(UUID scheduleId, UUID cattleId, String vaccineCode, Instant dueAt) {
        public OpenSchedule {
            if (scheduleId == null || cattleId == null || vaccineCode == null || dueAt == null) {
                throw new IllegalArgumentException("Invalid open schedule");
            }
        }
    }

    public record CattleInScope(CattleResource cattle, List<OpenSchedule> openSchedules) {
        public CattleInScope {
            if (cattle == null) throw new IllegalArgumentException("cattle is required");
            openSchedules = List.copyOf(openSchedules);
        }
    }
}
