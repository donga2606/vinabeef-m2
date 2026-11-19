package vn.edu.hcmut.vinabeef.health.adapter.web;

import java.util.List;
import java.util.UUID;

public record HealthHistoryResponse(UUID cattleId, List<HealthRecordResponse> records, Long nextBeforeVersion) {
    public HealthHistoryResponse { records = List.copyOf(records); }
}
