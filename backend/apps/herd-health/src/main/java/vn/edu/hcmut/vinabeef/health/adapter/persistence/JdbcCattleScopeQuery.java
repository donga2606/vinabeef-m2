package vn.edu.hcmut.vinabeef.health.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.edu.hcmut.vinabeef.health.application.port.out.CattleScopeQueryPort;
import vn.edu.hcmut.vinabeef.health.domain.CattleScopeModel.OpenSchedule;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.RegistrationStatus;

@Repository
public class JdbcCattleScopeQuery implements CattleScopeQueryPort {
    /** Upper bound per request; a farm-scale list (~25k) belongs to paging, not a phone cache. */
    static final int MAX_ROWS = 5000;

    private final JdbcClient jdbc;

    public JdbcCattleScopeQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CattleResource> findActiveCattleInFarms(Set<String> farmIds) {
        if (farmIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT c.cattle_id, c.rfid, c.display_name, c.registration_status,
                       c.resource_version, cc.custody_version, cc.farm_id, f.farm_name, cc.barn_id
                FROM cattle c
                JOIN cattle_custody cc ON cc.cattle_id = c.cattle_id
                JOIN farm f ON f.farm_id = cc.farm_id
                WHERE c.registration_status = 'ACTIVE' AND cc.farm_id IN (:farmIds)
                ORDER BY cc.farm_id, cc.barn_id, c.rfid
                LIMIT :limit
                """)
                .param("farmIds", farmIds)
                .param("limit", MAX_ROWS)
                .query(this::mapCattle)
                .list();
    }

    @Override
    public List<OpenSchedule> findOpenSchedules(Collection<UUID> cattleIds) {
        if (cattleIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT s.schedule_id, s.cattle_id, s.vaccine_code, s.due_at
                FROM vaccination_schedule s
                LEFT JOIN schedule_completion sc ON sc.schedule_id = s.schedule_id
                WHERE s.status = 'SCHEDULED' AND sc.schedule_id IS NULL AND s.cattle_id IN (:cattleIds)
                ORDER BY s.due_at, s.schedule_id
                """)
                .param("cattleIds", cattleIds)
                .query((rs, ignored) -> new OpenSchedule(rs.getObject("schedule_id", UUID.class),
                        rs.getObject("cattle_id", UUID.class), rs.getString("vaccine_code"),
                        rs.getTimestamp("due_at").toInstant()))
                .list();
    }

    private CattleResource mapCattle(ResultSet rs, int ignored) throws SQLException {
        return new CattleResource(rs.getObject("cattle_id", UUID.class), rs.getString("rfid"),
                rs.getString("display_name"), RegistrationStatus.valueOf(rs.getString("registration_status")),
                rs.getString("farm_id"), rs.getString("farm_name"), rs.getString("barn_id"),
                rs.getLong("resource_version"), rs.getLong("custody_version"));
    }
}
