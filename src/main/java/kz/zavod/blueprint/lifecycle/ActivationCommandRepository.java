package kz.zavod.blueprint.lifecycle;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivationCommandRepository extends JpaRepository<ActivationCommandEntity,String> {
    Optional<ActivationCommandEntity> findBySiteIdAndCommandKey(String siteId,String commandKey);
    long countBySiteId(String siteId);
}
