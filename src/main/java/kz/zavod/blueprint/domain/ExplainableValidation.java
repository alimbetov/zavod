package kz.zavod.blueprint.domain;

import java.util.*;
import kz.zavod.catalog.EquipmentCatalog;

/** Deterministic structured explanations, not generated advice or safety certification. */
public final class ExplainableValidation {
    private ExplainableValidation(){}
    public record Explanation(String code,String entity,String severity,String problem,
                              String consequence,String suggestedAction) {}
    private record Guide(String problem,String consequence,String action) {}
    private static final Map<String,Guide> GUIDES=Map.ofEntries(
        Map.entry("UNKNOWN_NODE",new Guide("Connection references missing equipment",
                "Stream topology is incomplete","Reconnect to an existing equipment ID")),
        Map.entry("MISSING_REQUIRED_PORT",new Guide("Mandatory equipment port is disconnected",
                "The configured flow path is incomplete","Connect a compatible port")),
        Map.entry("INCOMPATIBLE_STREAM",new Guide("Ports have incompatible media or flow kind",
                "Material or signal cannot be represented by this connection","Choose compatible port templates")),
        Map.entry("MATERIAL_CYCLE",new Guide("Unqualified material recirculation",
                "Material routing cannot be approved","Use a qualified CIRCUIT with documented recirculation")),
        Map.entry("RESOURCE_CAPACITY",new Guide("Shared resource capacity exceeded",
                "The selection cannot operate within configured capacity","Reduce demand or revise resource capacity with evidence")),
        Map.entry("INVALID_MEASUREMENT_SENSOR",new Guide("Measurement source has no sensor capability",
                "Observations would be attributed to unqualified equipment","Choose a catalog sensor with MEASURE capability")),
        Map.entry("GOLDEN_MISSING_PUMP",new Guide("Pump profile lacks a pump",
                "Golden topology does not match the intended process","Add a catalog water-pump")),
        Map.entry("GOLDEN_SOURCE_DISCONNECTED",new Guide("No source-to-pump flow",
                "The pump has no valid configured inlet path","Connect tank outlet to pump water-in")),
        Map.entry("GOLDEN_DISCHARGE_DISCONNECTED",new Guide("No pump-to-isolation flow",
                "Discharge isolation path is missing","Connect pump outlet to shutoff valve")));
    public static List<Explanation> inspect(ProcessBlueprint blueprint,EquipmentCatalog catalog) {
        List<Explanation> result=new ArrayList<>();
        for(BlueprintValidator.Finding f:BlueprintValidator.validate(blueprint))
            result.add(explain(f.code(),f.entity(),f.severity().name()));
        for(BlueprintCatalogAudit.Finding f:BlueprintCatalogAudit.inspect(blueprint,catalog))
            result.add(explain(f.code(),f.unitId()+"/"+f.equipmentId(),"ERROR"));
        for(EngineeringGoldenModel.Finding f:EngineeringGoldenModel.inspect(blueprint,catalog).findings())
            if(f.code().startsWith("GOLDEN_"))result.add(explain(f.code(),f.subject(),"WARNING"));
        result.sort(Comparator.comparing(Explanation::entity).thenComparing(Explanation::code));
        return List.copyOf(result);
    }
    private static Explanation explain(String code,String entity,String severity) {
        Guide guide=GUIDES.getOrDefault(code,new Guide("Engineering rule: "+code,
                "Publication readiness may be affected","Inspect the referenced entity and engineering catalog rule"));
        return new Explanation(code,entity,severity,guide.problem(),guide.consequence(),guide.action());
    }
}
