package vn.edu.hcmut.vinabeef.health.application.port.in;

import java.util.UUID;
import vn.edu.hcmut.vinabeef.health.domain.HealthModel.HealthReceipt;

public interface GetHealthIntentReceiptUseCase {
    HealthReceipt receipt(UUID intentId);
}
