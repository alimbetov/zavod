package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BlueprintApprovalRepository extends JpaRepository<BlueprintApprovalEntity,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BlueprintApprovalEntity a where a.id=:id")
    Optional<BlueprintApprovalEntity> lockApproval(@Param("id") String id);
}
