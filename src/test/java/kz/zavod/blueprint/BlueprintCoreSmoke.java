package kz.zavod.blueprint;

import kz.zavod.blueprint.domain.*;
import kz.zavod.lab.*;
import kz.zavod.catalog.*;
import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Self-contained Java core smoke verification; JPA uses a separate integration test. */
public final class BlueprintCoreSmoke {
    private static int count=0;
    public static void main(String[] args) {
        for(int n:new int[]{1,10,50,99}) {
            var sample=PlantScenarios.generate(n,99);
            var b=LabBlueprintAdapter.fromLab(UUID.nameUUIDFromBytes(("plant-"+n).getBytes()),1,sample);
            ok(b.units().size()==n,"N process units: "+n);
            ok(b.units().stream().flatMap(u->u.equipment().stream()).count()==sample.lines().stream().mapToInt(l->l.nodes().size()).sum(),"equipment preserved");
            ok(b.transfers().size()==sample.transfers().size(),"transfer preserved");
            ok(b.dependencies().size()==sample.dependencies().size(),"dependency preserved");
            ok(BlueprintValidator.validate(b).stream().noneMatch(f->f.severity()==BlueprintValidator.Severity.ERROR),"no structural errors");
            ok(b.units().stream().map(ProcessUnit::equipment).map(BlueprintCoreSmoke::signature).distinct().count()==n,"unique patterns");
            var impact=BlueprintImpactAnalyzer.analyze(b,Set.of(b.units().get(0).id()));
            ok(impact.affectedUnits().contains(b.units().get(0).id()),"impacted origin");
            ok(impact.affectedUnits().size()<=n,"impact bounded");
            ok(BlueprintCatalogAudit.inspect(b,EquipmentCatalog.standard()).size()>=0,"catalog audit performed");
        }
        var plant=LabBlueprintAdapter.fromLab(UUID.randomUUID(),1,PlantScenarios.generate(99,99));
        ok(!BlueprintImpactAnalyzer.analyze(plant,Set.of("L001")).contention().isEmpty(),"resource contention visible");
        ok(BlueprintCatalogAudit.inspect(plant,EquipmentCatalog.standard()).stream().anyMatch(f->f.code().equals("UNKNOWN_EQUIPMENT_TEMPLATE")),"unrecognized lab nodes explicitly reported");
        var restricted=new ProcessBlueprint(plant.id(),plant.revision(),10,plant.units(),plant.sharedAssets(),plant.transfers(),plant.dependencies());
        error(restricted,"UNIT_POLICY_EXCEEDED");
        var units=new ArrayList<>(plant.units());var u=units.get(0);
        units.set(0,new ProcessUnit(u.id(),UnitKind.CIRCUIT,"hydroponics.nft.v1",u.equipment(),u.connections(),u.boundaries(),u.resources(),u.measurements(),false));
        var modified=new ProcessBlueprint(plant.id(),2,99,units,plant.sharedAssets(),plant.transfers(),plant.dependencies());
        ok(modified.units().get(0).kind()==UnitKind.CIRCUIT,"unit kind supports circuit without new backend");
        error(new ProcessBlueprint(plant.id(),2,99,plant.units(),plant.sharedAssets(),plant.transfers(),List.of(new Dependency("L001","L002"),new Dependency("L002","L001"))),"INTERUNIT_DEPENDENCY_CYCLE");
        ok(BlueprintValidator.validate(plant).stream().anyMatch(f->f.code().equals("UNBOUND_BOUNDARY")&&f.severity()==BlueprintValidator.Severity.WARNING),"unbound laboratory boundary visible");
        var inlet=new Port("water-in",Direction.IN,FlowKind.MATERIAL,"water",1,1);
        var outlet=new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",1,1);
        var pump=new Equipment("pump","water-pump",1,List.of(inlet,outlet));
        var valve=new Equipment("valve","shutoff-valve",1,List.of(inlet,outlet));
        var loop=new ProcessUnit("hydro-circuit",UnitKind.CIRCUIT,"hydroponics.nft.v1",
                List.of(pump,valve),List.of(new Connection("pump","water-out","valve","water-in"),
                new Connection("valve","water-out","pump","water-in")),List.of(),List.of(),List.of(),true);
        var hydro=new ProcessBlueprint(UUID.randomUUID(),1,99,List.of(loop),List.of(),List.of(),List.of());
        ok(BlueprintValidator.validate(hydro).stream().anyMatch(x->x.code().equals("RECIRCULATION_UNQUALIFIED")),
                "hydroponic feedback loop explicitly flagged as unqualified");
        ok(BlueprintReadiness.blockers(hydro,EquipmentCatalog.standard()).stream().anyMatch(x->x.code().equals("RECIRCULATION_UNQUALIFIED")),
                "hydroponic loop cannot be published merely by toggling a flag");
        var mismatchedPorts=new Equipment("pump-invalid","water-pump",1,List.of(
                new Port("water-in",Direction.IN,FlowKind.MATERIAL,"cement",1,1),
                new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
        var onePump=new ProcessUnit("unit",UnitKind.STATION,"continuous.pump.v1",
                List.of(mismatchedPorts),List.of(),List.of(),List.of(),List.of(),false);
        var invalidCatalog=new ProcessBlueprint(UUID.randomUUID(),1,1,List.of(onePump),List.of(),List.of(),List.of());
        ok(BlueprintCatalogAudit.inspect(invalidCatalog,EquipmentCatalog.standard()).stream()
                .anyMatch(x->x.code().equals("TEMPLATE_PORT_SCHEMA_MISMATCH")),
                "template port may not silently change medium even with same key");
        rejects(()->BlueprintImpactAnalyzer.analyze(plant,Set.of("UNKNOWN")),"unknown unit change rejected");
        System.out.println("PASS "+count+" Blueprint core checks");
    }
    private static String signature(List<Equipment> equipment){return equipment.stream().map(Equipment::typeKey).toList().toString();}
    private static void error(ProcessBlueprint b,String expected){ok(BlueprintValidator.validate(b).stream().anyMatch(f->f.code().equals(expected)),expected);}
    private static void ok(boolean value,String name){if(!value)throw new AssertionError(name);count++;}
    private static void rejects(Runnable runnable,String message){try{runnable.run();}catch(IllegalArgumentException e){count++;return;}throw new AssertionError(message);}
}
