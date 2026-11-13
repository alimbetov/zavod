package kz.zavod.blueprint.domain;

import java.util.*;
import kz.zavod.catalog.EquipmentCatalog;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/**
 * Pure server-side publication preflight. No command to a PLC is ever issued.
 * This does not constitute approval or activation persistence.
 */
public final class BlueprintPublicationGate {
    private BlueprintPublicationGate(){}
    public record Finding(String code,String entity) {}

    public static List<Finding> preflight(ProcessBlueprint blueprint,Set<String> activeIds,
                                          int serverMaxActiveLines,EquipmentCatalog catalog) {
        List<Finding> findings=new ArrayList<>();
        for(BlueprintActivationPolicy.Finding f:BlueprintActivationPolicy.validate(
                blueprint,activeIds,serverMaxActiveLines))
            findings.add(new Finding(f.code(),f.subject()));
        Set<String> known=new HashSet<>();
        List<ProcessUnit> selected=new ArrayList<>();
        for(ProcessUnit unit:blueprint.units())if(activeIds.contains(unit.id())) {
            known.add(unit.id());selected.add(unit);
        }
        if(selected.isEmpty())findings.add(new Finding("NO_ACTIVE_UNITS",blueprint.id().toString()));
        for(Transfer t:blueprint.transfers()) {
            if(activeIds.contains(t.sourceUnit())!=activeIds.contains(t.targetUnit()))
                findings.add(new Finding("INACTIVE_TRANSFER_PEER",t.sourceUnit()+"->"+t.targetUnit()));
        }
        for(Dependency d:blueprint.dependencies()) {
            if(activeIds.contains(d.sourceUnit())!=activeIds.contains(d.targetUnit()))
                findings.add(new Finding("INACTIVE_DEPENDENCY_PEER",d.sourceUnit()+"->"+d.targetUnit()));
        }
        List<Transfer> transfers=blueprint.transfers().stream()
                .filter(t->known.contains(t.sourceUnit())&&known.contains(t.targetUnit())).toList();
        List<Dependency> dependencies=blueprint.dependencies().stream()
                .filter(d->known.contains(d.sourceUnit())&&known.contains(d.targetUnit())).toList();
        Set<String> usedAssets=new HashSet<>();
        for(ProcessUnit unit:selected)for(ResourceUse use:unit.resources())usedAssets.add(use.sharedAssetId());
        List<SharedAsset> assets=blueprint.sharedAssets().stream()
                .filter(a->usedAssets.contains(a.id())).toList();
        ProcessBlueprint subset=new ProcessBlueprint(blueprint.id(),blueprint.revision(),serverMaxActiveLines,
                selected,assets,transfers,dependencies);
        for(BlueprintReadiness.Finding f:BlueprintReadiness.blockers(subset,catalog))
            findings.add(new Finding(f.code(),f.ref()));
        return List.copyOf(findings);
    }
}
