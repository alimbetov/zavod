package kz.zavod.blueprint.domain;

import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/**
 * Read-only engineering HMI schematic. Does not generate live tags, operator
 * commands, safety indications or a physical equipment state.
 */
public final class HmiPreviewGenerator {
    private HmiPreviewGenerator(){}
    public record EquipmentNode(String id,String template,String label,String state) {}
    public record Link(String from,String to,String medium) {}
    public record LinePreview(String unitId,List<EquipmentNode> equipment,List<Link> connections) {
        public LinePreview {
            equipment=List.copyOf(equipment);connections=List.copyOf(connections);
        }
    }
    public record Preview(UUID blueprintId,long revision,String mode,boolean live,
                          List<LinePreview> lines) {
        public Preview {lines=List.copyOf(lines);}
    }
    public static Preview generate(ProcessBlueprint blueprint,Set<String> approvedUnits) {
        List<LinePreview> lines=new ArrayList<>();
        for(ProcessUnit unit:blueprint.units()) {
            if(!approvedUnits.contains(unit.id()))continue;
            List<EquipmentNode> equipment=unit.equipment().stream()
                    .map(e->new EquipmentNode(e.id(),e.typeKey(),e.typeKey(),"UNKNOWN_NO_TELEMETRY"))
                    .toList();
            Map<String,Equipment> indexed=new HashMap<>();
            unit.equipment().forEach(e->indexed.put(e.id(),e));
            List<Link> connections=new ArrayList<>();
            for(Connection c:unit.connections()) {
                var source=indexed.get(c.sourceEquipment());
                String medium="UNRESOLVED";
                if(source!=null)for(Port p:source.ports())
                    if(p.key().equals(c.sourcePort()))medium=p.medium();
                connections.add(new Link(c.sourceEquipment(),c.targetEquipment(),medium));
            }
            lines.add(new LinePreview(unit.id(),equipment,connections));
        }
        lines.sort(Comparator.comparing(LinePreview::unitId));
        return new Preview(blueprint.id(),blueprint.revision(),"ENGINEERING_PREVIEW",false,lines);
    }
}
