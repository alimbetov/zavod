package kz.zavod.blueprint.lifecycle;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessTransitionRepository extends JpaRepository<BusinessTransitionEntity,String> {
    Optional<BusinessTransitionEntity> findTopBySiteIdOrderBySequenceNoDesc(String siteId);
    List<BusinessTransitionEntity> findBySiteIdOrderBySequenceNoAsc(String siteId);
    long countBySiteId(String siteId);
    Optional<BusinessTransitionEntity> findBySiteIdAndCommandKey(String siteId,String commandKey);
    Optional<BusinessTransitionEntity> findBySiteIdAndSequenceNo(String siteId,long sequenceNo);
}
