package kz.zavod.studio;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface StudioLayoutRepository extends JpaRepository<StudioLayoutEntity,String> {
    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("update StudioLayoutEntity l set l.layoutVersion=:next,l.blueprintRevision=:revision,"+
           "l.layoutJson=:data where l.id=:id and l.layoutVersion=:expected")
    int compareAndUpdate(@Param("id") String id,@Param("expected") long expected,
                         @Param("next") long next,@Param("revision") long revision,
                         @Param("data") String data);
}
