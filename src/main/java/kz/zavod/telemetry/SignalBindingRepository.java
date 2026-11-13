package kz.zavod.telemetry;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignalBindingRepository extends JpaRepository<SignalBindingEntity,String> {
    List<SignalBindingEntity> findBySiteIdAndBlueprintIdAndRevisionNoOrderByUnitIdAscEquipmentIdAscSignalKeyAsc(
            String siteId,String blueprintId,long revisionNo);
}
