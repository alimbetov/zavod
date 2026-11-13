package kz.zavod.studio;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EngineeringProjectRepository extends JpaRepository<EngineeringProjectEntity,String> {
    List<EngineeringProjectEntity> findBySiteIdAndStatusOrderByCreatedAtAsc(
            String siteId,EngineeringProjectEntity.Status status);

    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("update EngineeringProjectEntity p set p.name=:name,p.description=:description,"+
            "p.rowVersion=p.rowVersion+1,p.updatedAt=:now "+
            "where p.projectId=:id and p.siteId=:site and p.status=:status and p.rowVersion=:expected")
    int compareAndUpdate(@Param("id") String id,@Param("site") String site,
                         @Param("name") String name,@Param("description") String description,
                         @Param("now") Instant now,@Param("status") EngineeringProjectEntity.Status status,
                         @Param("expected") long expected);

    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("update EngineeringProjectEntity p set p.status=:archived,p.archivedAt=:now,"+
            "p.updatedAt=:now,p.rowVersion=p.rowVersion+1 "+
            "where p.projectId=:id and p.siteId=:site and p.status=:active and p.rowVersion=:expected")
    int compareAndArchive(@Param("id") String id,@Param("site") String site,
                          @Param("expected") long expected,@Param("now") Instant now,
                          @Param("active") EngineeringProjectEntity.Status active,
                          @Param("archived") EngineeringProjectEntity.Status archived);
}
