package kz.zavod.blueprint.domain;

import kz.zavod.catalog.EquipmentCatalog;
import kz.zavod.catalog.EquipmentType;
import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Allows save of an incomplete draft, but reports unresolved catalog bindings before approval. */
public final class BlueprintCatalogAudit {
    private BlueprintCatalogAudit() {}
    public record Finding(String code,String unitId,String equipmentId,String detail) {}

    public static List<Finding> inspect(ProcessBlueprint b, EquipmentCatalog catalog) {
        List<Finding> findings = new ArrayList<>();
        for(ProcessUnit u:b.units())for(Equipment e:u.equipment()) {
            EquipmentType type=catalog.types().get(e.typeKey());
            if(type==null){findings.add(new Finding("UNKNOWN_EQUIPMENT_TEMPLATE",u.id(),e.id(),e.typeKey()));continue;}
            if(type.version()!=e.templateVersion()){
                findings.add(new Finding("TEMPLATE_VERSION_MISMATCH",u.id(),e.id(),e.typeKey()));continue;
            }
            Set<String> portKeys=new HashSet<>();
            for(EquipmentType.Port p:type.ports())portKeys.add(p.key());
            for(Port p:e.ports())if(!portKeys.contains(p.key()))
                findings.add(new Finding("UNRECOGNIZED_CATALOG_PORT",u.id(),e.id(),p.key()));
        }
        return List.copyOf(findings);
    }
}
