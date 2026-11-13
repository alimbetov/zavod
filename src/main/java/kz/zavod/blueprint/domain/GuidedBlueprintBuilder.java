package kz.zavod.blueprint.domain;

import java.util.*;
import kz.zavod.catalog.EquipmentCatalog;
import kz.zavod.catalog.EquipmentType;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/**
 * Only approved structural starter templates, not a physical process design
 * or autonomous approval. The engineer must still supply qualified properties.
 */
public final class GuidedBlueprintBuilder {
    private GuidedBlueprintBuilder() {}
    public static Set<String> templates(){return Set.of("continuous.pump.v1");}
    public static ProcessBlueprint pumpStarter(UUID id,long revision,int maxActiveLines,String unitId,
                                               EquipmentCatalog catalog) {
        if(unitId==null||!unitId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("INVALID_UNIT_ID");
        List<Equipment> devices=List.of(
                equipment("tank",catalog.type("water-tank",1)),
                equipment("pump",catalog.type("water-pump",1)),
                equipment("valve",catalog.type("shutoff-valve",1)));
        ProcessUnit unit=new ProcessUnit(unitId,UnitKind.LINE,"continuous.pump.v1",devices,
                List.of(new Connection("tank","water-out","pump","water-in"),
                        new Connection("pump","water-out","valve","water-in")),
                List.of(),List.of(),List.of(),false);
        return new ProcessBlueprint(id,revision,maxActiveLines,List.of(unit),
                List.of(),List.of(),List.of());
    }
    private static Equipment equipment(String id,EquipmentType definition) {
        var ports=definition.ports().stream().map(p->new Port(p.key(),
                Direction.valueOf(p.direction().name()),FlowKind.valueOf(p.streamKind().name()),
                p.medium(),p.minConnections(),p.maxConnections())).toList();
        return new Equipment(id,definition.key(),definition.version(),ports,List.of());
    }
}
