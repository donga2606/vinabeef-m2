package vn.edu.hcmut.vinabeef.health.adapter.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.vinabeef.health.application.port.in.ListCattleInScopeUseCase;
import vn.edu.hcmut.vinabeef.health.domain.CattleScopeModel.CattleInScope;
import vn.edu.hcmut.vinabeef.health.domain.CattleScopeModel.OpenSchedule;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;

/** GET /api/v1/cattle/in-scope – work-session preparation for the mobile capture app. */
@RestController
@RequestMapping("/api/v1")
public class CattleScopeController {
    private final ListCattleInScopeUseCase listCattleInScope;

    public CattleScopeController(ListCattleInScopeUseCase listCattleInScope) {
        this.listCattleInScope = listCattleInScope;
    }

    @GetMapping("/cattle/in-scope")
    public ResponseEntity<CattleInScopePage> inScope() {
        List<CattleInScopeItem> items = listCattleInScope.listInScope().stream()
                .map(CattleInScopeItem::from).toList();
        return ResponseEntity.ok(new CattleInScopePage(items));
    }

    public record CattleInScopePage(List<CattleInScopeItem> items) {
        public CattleInScopePage { items = List.copyOf(items); }
    }

    public record CattleInScopeItem(UUID cattleId, String rfid, String displayName, String registrationStatus,
                                    String farmId, String farmName, String barnId, long resourceVersion,
                                    long custodyVersion, List<OpenScheduleItem> openSchedules) {
        static CattleInScopeItem from(CattleInScope value) {
            CattleResource c = value.cattle();
            return new CattleInScopeItem(c.cattleId(), c.rfid(), c.displayName(), c.registrationStatus().name(),
                    c.farmId(), c.farmName(), c.barnId(), c.resourceVersion(), c.custodyVersion(),
                    value.openSchedules().stream().map(OpenScheduleItem::from).toList());
        }
    }

    public record OpenScheduleItem(UUID scheduleId, String vaccineCode, Instant dueAt) {
        static OpenScheduleItem from(OpenSchedule schedule) {
            return new OpenScheduleItem(schedule.scheduleId(), schedule.vaccineCode(), schedule.dueAt());
        }
    }
}
