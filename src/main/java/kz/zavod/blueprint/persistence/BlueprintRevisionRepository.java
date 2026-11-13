package kz.zavod.blueprint.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlueprintRevisionRepository extends JpaRepository<BlueprintRevisionEntity,String> {
    Optional<BlueprintRevisionEntity> findTopByBlueprintIdOrderByRevisionNoDesc(String blueprintId);
    Optional<BlueprintRevisionEntity> findByBlueprintIdAndRevisionNo(String blueprintId,long revisionNo);
}
