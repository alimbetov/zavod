package kz.zavod.blueprint.domain;

import kz.zavod.catalog.EquipmentCatalog;
import java.util.List;

/** Publication decision is stricter than persistence of a draft. */
public final class BlueprintReadiness {
    private BlueprintReadiness() {}
    public record Finding(String code,String ref) {}

    public static List<Finding> blockers(ProcessBlueprint blueprint,EquipmentCatalog catalog) {
        var topology=BlueprintValidator.validate(blueprint).stream()
                .filter(f->f.severity()==BlueprintValidator.Severity.ERROR || f.code().equals("UNBOUND_BOUNDARY"))
                .map(f->new Finding(f.code(),f.entity())).toList();
        var library=BlueprintCatalogAudit.inspect(blueprint,catalog).stream()
                .map(f->new Finding(f.code(),f.unitId()+"/"+f.equipmentId())).toList();
        var findings=new java.util.ArrayList<Finding>(topology);
        findings.addAll(library);
        return List.copyOf(findings);
    }
}
