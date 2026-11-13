package kz.zavod.blueprint.lifecycle;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<SiteEntity,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SiteEntity s where s.siteId=:id")
    Optional<SiteEntity> lockSite(@Param("id") String siteId);
}
