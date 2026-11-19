package vn.edu.hcmut.vinabeef.health.application.port.in;

import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthIntentSubmission;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthReceipt;

public interface SubmitHealthIntentUseCase {
    SubmissionResult submit(HealthIntentSubmission submission);

    record SubmissionResult(int httpStatus, HealthReceipt receipt) {}
}
