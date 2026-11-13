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
    public static Set<String> templates(){return Set.of("continuous.pump.v1","batch.concrete.v1");}
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
    /**
     * Three dependent concrete production units, joined only through typed
     * public boundaries. This is a structural starter, NOT a physical process
     * operating-envelope or recipe qualification.
     */
    public static ProcessBlueprint concreteStarter(UUID id,long revision,int maxActiveLines,
                                                  String unitPrefix,EquipmentCatalog catalog) {
        if(unitPrefix==null||!unitPrefix.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("INVALID_UNIT_PREFIX");
        String aggregateId=unitPrefix+"-AGG";
        String cementId=unitPrefix+"-CEM";
        String mixingId=unitPrefix+"-MIX";
        ProcessUnit aggregate=new ProcessUnit(aggregateId,UnitKind.LINE,"batch.concrete.v1",
                List.of(equipment("aggregate-bin",catalog.type("aggregate-bin",1)),
                        equipment("aggregate-scale",catalog.type("aggregate-scale",1)),
                        equipment("conveyor",catalog.type("belt-conveyor",1))),
                List.of(new Connection("aggregate-bin","aggregate-out","aggregate-scale","aggregate-in"),
                        new Connection("aggregate-scale","aggregate-out","conveyor","aggregate-in")),
                List.of(new Boundary("aggregate-out",Direction.OUT,FlowKind.MATERIAL,"aggregate",
                        "conveyor","aggregate-out")),
                List.of(new ResourceUse("plant-power",3)),List.of(),false);
        ProcessUnit cement=new ProcessUnit(cementId,UnitKind.LINE,"batch.concrete.v1",
                List.of(equipment("cement-silo",catalog.type("cement-silo",1)),
                        equipment("cement-scale",catalog.type("cement-scale",1))),
                List.of(new Connection("cement-silo","cement-out","cement-scale","cement-in")),
                List.of(new Boundary("cement-out",Direction.OUT,FlowKind.MATERIAL,"cement",
                        "cement-scale","cement-out")),
                List.of(new ResourceUse("plant-power",2)),List.of(),false);
        ProcessUnit mixing=new ProcessUnit(mixingId,UnitKind.LINE,"batch.concrete.v1",
                List.of(equipment("water-tank",catalog.type("water-tank",1)),
                        equipment("water-doser",catalog.type("water-doser",1)),
                        equipment("mixer",catalog.type("batch-mixer",1)),
                        equipment("discharge",catalog.type("discharge",1))),
                List.of(new Connection("water-tank","water-out","water-doser","water-in"),
                        new Connection("water-doser","water-out","mixer","water-in"),
                        new Connection("mixer","product-out","discharge","product-in")),
                List.of(new Boundary("aggregate-in",Direction.IN,FlowKind.MATERIAL,"aggregate",
                                "mixer","aggregate-in"),
                        new Boundary("cement-in",Direction.IN,FlowKind.MATERIAL,"cement",
                                "mixer","cement-in")),
                List.of(new ResourceUse("plant-power",5)),List.of(),false);
        return new ProcessBlueprint(id,revision,maxActiveLines,
                List.of(aggregate,cement,mixing),
                List.of(new SharedAsset("plant-power",12,AssetMode.SHARED_CAPACITY)),
                List.of(new Transfer(aggregateId,"aggregate-out",mixingId,"aggregate-in"),
                        new Transfer(cementId,"cement-out",mixingId,"cement-in")),
                List.of());
    }

    private static Equipment equipment(String id,EquipmentType definition) {
        var ports=definition.ports().stream().map(p->new Port(p.key(),
                Direction.valueOf(p.direction().name()),FlowKind.valueOf(p.streamKind().name()),
                p.medium(),p.minConnections(),p.maxConnections())).toList();
        return new Equipment(id,definition.key(),definition.version(),ports,List.of());
    }
}
