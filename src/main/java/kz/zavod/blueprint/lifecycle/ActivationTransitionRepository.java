package kz.zavod.blueprint.lifecycle;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivationTransitionRepository extends JpaRepository<ActivationTransitionEntity,String> {
    Optional<ActivationTransitionEntity> findByCommandId(String commandId);
    long countBySiteId(String siteId);
}
