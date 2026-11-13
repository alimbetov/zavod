package kz.zavod.blueprint.domain;

import kz.zavod.catalog.EquipmentCatalog;
import kz.zavod.catalog.EquipmentType;
import kz.zavod.engineering.EngineeringPropertyAudit;
import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Allows save of an incomplete draft, but reports unresolved catalog bindings before approval. */
public final class BlueprintCatalogAudit {
    private BlueprintCatalogAudit() {}
    public record Finding(String code,String unitId,String equipmentId,String detail) {}

    public static List<Finding> inspect(ProcessBlueprint b, EquipmentCatalog catalog) {
        List<Finding> findings = new ArrayList<>();
        for(ProcessUnit u:b.units()) {
            for(Equipment e:u.equipment()) {
            EquipmentType type=catalog.versions().get(new EquipmentCatalog.TemplateId(e.typeKey(),e.templateVersion()));
            if(type==null) {
                String code=catalog.types().containsKey(e.typeKey())?"TEMPLATE_VERSION_MISMATCH":"UNKNOWN_EQUIPMENT_TEMPLATE";
                findings.add(new Finding(code,u.id(),e.id(),e.typeKey()+"/"+e.templateVersion()));
                continue;
            }
            Map<String,EquipmentType.Port> templatePorts=new HashMap<>();
            for(EquipmentType.Port p:type.ports())templatePorts.put(p.key(),p);
            Set<String> declared=new HashSet<>();
            for(Port p:e.ports()) {
                declared.add(p.key());
                EquipmentType.Port required=templatePorts.get(p.key());
                if(required==null) {
                    findings.add(new Finding("UNRECOGNIZED_CATALOG_PORT",u.id(),e.id(),p.key()));
                } else if(!required.direction().name().equals(p.direction().name())
                        || !required.streamKind().name().equals(p.kind().name())
                        || !required.medium().equals(p.medium())
                        || required.minConnections()!=p.min() || required.maxConnections()!=p.max()) {
                    findings.add(new Finding("TEMPLATE_PORT_SCHEMA_MISMATCH",u.id(),e.id(),p.key()));
                }
            }
            for(EquipmentType.Port p:type.ports())if(!declared.contains(p.key()))
                findings.add(new Finding("MISSING_TEMPLATE_PORT",u.id(),e.id(),p.key()));
            for(EngineeringPropertyAudit.Finding property:EngineeringPropertyAudit.inspect(type,e.properties()))
                findings.add(new Finding(property.code(),u.id(),e.id(),property.propertyKey()));
            }
            Map<String,Equipment> byId=new HashMap<>();
            for(Equipment e:u.equipment())byId.putIfAbsent(e.id(),e);
            for(Measurement measurement:u.measurements()) {
                String sensorId=measurement.sensorEquipmentId();
                if(measurement.signalKey()==null||measurement.signalKey().isBlank())
                    findings.add(new Finding("INVALID_MEASUREMENT_SIGNAL",u.id(),sensorId,
                            String.valueOf(measurement.signalKey())));
                Equipment sensor=byId.get(sensorId);
                if(sensor==null)continue; // topology validator reports UNKNOWN_MEASUREMENT_COMPONENT
                EquipmentType sensorType=catalog.versions().get(
                        new EquipmentCatalog.TemplateId(sensor.typeKey(),sensor.templateVersion()));
                if(sensorType!=null&&!sensorType.capabilities().contains(EquipmentType.Capability.MEASURE))
                    findings.add(new Finding("INVALID_MEASUREMENT_SENSOR",u.id(),sensorId,sensor.typeKey()));
            }
        }
        return List.copyOf(findings);
    }
}
