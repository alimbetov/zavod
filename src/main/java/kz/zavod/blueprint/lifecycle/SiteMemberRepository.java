package kz.zavod.blueprint.lifecycle;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteMemberRepository extends JpaRepository<SiteMemberEntity,String> {
    Optional<SiteMemberEntity> findBySiteIdAndActorId(String siteId,String actorId);
}
