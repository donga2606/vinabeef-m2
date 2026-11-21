package vn.edu.hcmut.vinabeef.health.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.vinabeef.health.application.port.in.GetHealthHistoryUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.GetHealthIntentReceiptUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.LookupCattleUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.SubmitHealthIntentUseCase;
import vn.edu.hcmut.vinabeef.health.application.port.in.SubmitHealthIntentUseCase.SubmissionResult;
import vn.edu.hcmut.vinabeef.health.domain.HealthException;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthReceipt;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HistoryPage;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
    private final LookupCattleUseCase lookupCattle;
    private final SubmitHealthIntentUseCase submitHealthIntent;
    private final GetHealthIntentReceiptUseCase getHealthIntentReceipt;
    private final GetHealthHistoryUseCase getHealthHistory;
    private final ObjectMapper objectMapper;

    public HealthController(LookupCattleUseCase lookupCattle, SubmitHealthIntentUseCase submitHealthIntent,
                            GetHealthIntentReceiptUseCase getHealthIntentReceipt,
                            GetHealthHistoryUseCase getHealthHistory, ObjectMapper objectMapper) {
        this.lookupCattle = lookupCattle;
        this.submitHealthIntent = submitHealthIntent;
        this.getHealthIntentReceipt = getHealthIntentReceipt;
        this.getHealthHistory = getHealthHistory;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/cattle")
    public ResponseEntity<?> lookupCattle(@RequestParam String rfid) {
        return ResponseEntity.ok(lookupCattle.lookupByRfid(rfid));
    }

    @PostMapping("/health-intents")
    public ResponseEntity<?> submit(@Valid @RequestBody HealthIntentRequest request) {
        SubmissionResult result = submitHealthIntent.submit(request.toSubmission(objectMapper));
        if (result.httpStatus() == 409) {
            HealthReceipt receipt = result.receipt();
            ProblemResponse problem = ProblemResponse.from(409, receipt.reasonCode(),
                    "This intent needs review before it can be accepted.", receipt);
            return ResponseEntity.status(409).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
        }
        return ResponseEntity.status(result.httpStatus()).body(result.receipt());
    }

    @GetMapping("/health-intents/{intentId}")
    public ResponseEntity<HealthReceipt> receipt(@PathVariable UUID intentId) {
        return ResponseEntity.ok(getHealthIntentReceipt.receipt(intentId));
    }

    @GetMapping("/cattle/{cattleId}/health-history")
    public ResponseEntity<HealthHistoryResponse> history(
            @PathVariable UUID cattleId,
            @RequestParam(required = false) Long beforeVersion,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        HistoryPage page = getHealthHistory.history(cattleId, beforeVersion, limit);
        List<HealthRecordResponse> records = page.records().stream()
                .map(record -> HealthRecordResponse.from(record, objectMapper)).toList();
        return ResponseEntity.ok(new HealthHistoryResponse(page.cattleId(), records, page.nextBeforeVersion()));
    }
}
