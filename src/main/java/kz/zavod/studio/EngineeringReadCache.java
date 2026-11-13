package kz.zavod.studio;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kz.zavod.blueprint.domain.BlueprintImpactAnalyzer;
import kz.zavod.blueprint.domain.ProcessBlueprint;
import kz.zavod.catalog.EquipmentCatalog;
import org.springframework.stereotype.Component;

/**
 * Safe, bounded, process-local read-through caches. All authorization and
 * SHA verification happen before lookup. Never cache mutable lifecycle pointers.
 */
@Component
public final class EngineeringReadCache {
    private record DiffKey(UUID blueprintId,long from,long to) {}
    private final Cache<String,List<StudioDevController.EquipmentTypeDto>> catalog=
            Caffeine.newBuilder().maximumSize(4).expireAfterWrite(Duration.ofMinutes(30))
                    .recordStats().build();
    private final Cache<DiffKey,BlueprintImpactAnalyzer.Impact> impacts=
            Caffeine.newBuilder().maximumSize(256).expireAfterWrite(Duration.ofMinutes(10))
                    .recordStats().build();

    public List<StudioDevController.EquipmentTypeDto> equipmentTypes(EquipmentCatalog source) {
        // Catalog is immutable, globally versioned source code at this stage.
        return catalog.get("standard-v1", key->source.versions().values().stream()
                .sorted(java.util.Comparator.comparing(kz.zavod.catalog.EquipmentType::key)
                        .thenComparingInt(kz.zavod.catalog.EquipmentType::version))
                .map(StudioDevController.EquipmentTypeDto::from).toList());
    }
    public BlueprintImpactAnalyzer.Impact impact(ProcessBlueprint before,ProcessBlueprint after) {
        if(!before.id().equals(after.id()) || before.revision()>=after.revision())
            throw new IllegalArgumentException("INVALID_REVISION_RANGE");
        // The calling service must have loaded and SHA-checked both exact revisions.
        return impacts.get(new DiffKey(before.id(),before.revision(),after.revision()),
                key->BlueprintImpactAnalyzer.analyzeChange(before,after));
    }
    public long impactHits(){return impacts.stats().hitCount();}
    public long catalogHits(){return catalog.stats().hitCount();}
}
