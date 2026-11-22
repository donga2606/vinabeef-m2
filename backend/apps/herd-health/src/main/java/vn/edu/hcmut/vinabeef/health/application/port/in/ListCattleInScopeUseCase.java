package vn.edu.hcmut.vinabeef.health.application.port.in;

import java.util.List;
import vn.edu.hcmut.vinabeef.health.domain.CattleScopeModel.CattleInScope;

/** Minimal list of animals the caller may currently view, used to prepare an offline capture window. */
public interface ListCattleInScopeUseCase {
    List<CattleInScope> listInScope();
}
