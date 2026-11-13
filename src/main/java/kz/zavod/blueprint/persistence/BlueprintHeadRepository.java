package kz.zavod.blueprint.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlueprintHeadRepository extends JpaRepository<BlueprintHeadEntity,String> {
    /** Atomic compare-and-swap. The PostgreSQL row update serializes competing writes. */
    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("update BlueprintHeadEntity h set h.revisionNo=:next, h.rowVersion=h.rowVersion+1 "+
           "where h.blueprintId=:id and h.revisionNo=:expected")
    int compareAndAdvance(@Param("id") String id,@Param("expected") long expected,
                          @Param("next") long next);
}
