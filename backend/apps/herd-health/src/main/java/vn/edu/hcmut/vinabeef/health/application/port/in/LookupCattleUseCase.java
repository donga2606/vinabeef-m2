package vn.edu.hcmut.vinabeef.health.application.port.in;

import vn.edu.hcmut.vinabeef.health.domain.HealthModel.CattleResource;

public interface LookupCattleUseCase {
    CattleResource lookupByRfid(String rfid);
}
