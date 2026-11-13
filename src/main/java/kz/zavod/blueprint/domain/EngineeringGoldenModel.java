package kz.zavod.blueprint.domain;

import java.util.*;
import kz.zavod.catalog.EquipmentCatalog;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/**
 * Structural golden oracle for the starter pump line. It proves topology and
 * catalogue compatibility, NOT hydraulic calculations, commissioning or safety.
 */
public final class EngineeringGoldenModel {
    private EngineeringGoldenModel(){}
    public record Finding(String code,String subject,String expected) {}
    public record Report(String model,boolean structurallyQualified,boolean physicallyQualified,
                         List<Finding> findings) {
        public Report { findings=List.copyOf(findings); }
    }
    public static Report inspect(ProcessBlueprint blueprint,EquipmentCatalog catalog) {
        List<Finding> failures=new ArrayList<>();
        for(BlueprintReadiness.Finding f:BlueprintReadiness.blockers(blueprint,catalog))
            failures.add(new Finding(f.code(),f.ref(),"Fix publication readiness"));
        for(ProcessUnit unit:blueprint.units()) {
            if(!unit.profileKey().equals("continuous.pump.v1"))continue;
            boolean tank=unit.equipment().stream().anyMatch(e->e.typeKey().equals("water-tank")
                    ||e.typeKey().equals("process-tank"));
            boolean pump=unit.equipment().stream().anyMatch(e->e.typeKey().equals("water-pump"));
            boolean isolation=unit.equipment().stream().anyMatch(e->e.typeKey().equals("shutoff-valve"));
            if(!tank)failures.add(new Finding("GOLDEN_MISSING_SOURCE",unit.id(),"water-tank or process-tank"));
            if(!pump)failures.add(new Finding("GOLDEN_MISSING_PUMP",unit.id(),"water-pump"));
            if(!isolation)failures.add(new Finding("GOLDEN_MISSING_ISOLATION",unit.id(),"shutoff-valve"));
            Map<String,Equipment> equipment=new HashMap<>();
            unit.equipment().forEach(e->equipment.put(e.id(),e));
            boolean inlet=unit.connections().stream().anyMatch(c->{
                var a=equipment.get(c.sourceEquipment());var b=equipment.get(c.targetEquipment());
                return a!=null&&b!=null&&Set.of("water-tank","process-tank").contains(a.typeKey())
                        &&b.typeKey().equals("water-pump")&&c.targetPort().equals("water-in");
            });
            boolean outlet=unit.connections().stream().anyMatch(c->{
                var a=equipment.get(c.sourceEquipment());var b=equipment.get(c.targetEquipment());
                return a!=null&&b!=null&&a.typeKey().equals("water-pump")
                        &&b.typeKey().equals("shutoff-valve")&&c.sourcePort().equals("water-out");
            });
            if(!inlet)failures.add(new Finding("GOLDEN_SOURCE_DISCONNECTED",unit.id(),"tank -> pump inlet"));
            if(!outlet)failures.add(new Finding("GOLDEN_DISCHARGE_DISCONNECTED",unit.id(),"pump outlet -> valve"));
        }
        // Absolute pressure / flow / power relationships require engineering evidence and
        // system curves; topology alone must never report physicallyQualified=true.
        return new Report("continuous.pump.v1",failures.isEmpty(),false,failures);
    }
}
