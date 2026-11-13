package kz.zavod.blueprint;

import java.util.*;
import kz.zavod.blueprint.domain.*;
import kz.zavod.catalog.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Independent golden oracles for architectural hardening (not production HIL tests). */
public final class BlueprintHardeningSmoke {
    private static int assertions=0;
    private static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);assertions++;}
    private static ProcessUnit line(String id,List<ResourceUse> resources){
        return new ProcessUnit(id,UnitKind.LINE,"test.v1",List.of(),List.of(),List.of(),resources,List.of(),false);
    }
    private static ProcessBlueprint blueprint(UUID id,int revision,List<ProcessUnit> units,
                                               List<SharedAsset> assets,List<Transfer> links) {
        return new ProcessBlueprint(id,revision,10,units,assets,links,List.of());
    }
    public static void main(String[] args) {
        UUID id=UUID.randomUUID();
        var asset=new SharedAsset("compressor",3,AssetMode.SHARED_CAPACITY);
        var a=line("L037",List.of(new ResourceUse("compressor",1)));
        var b=line("L040",List.of());
        var c=line("L043",List.of());
        var d=line("L012",List.of(new ResourceUse("compressor",1)));
        var e=line("L055",List.of(new ResourceUse("compressor",1)));
        var unrelated=line("L099",List.of());
        var links=List.of(new Dependency("L037","L040"),new Dependency("L040","L043"));
        ProcessBlueprint baseline=new ProcessBlueprint(id,1,10,List.of(a,b,c,d,e,unrelated),
                List.of(asset),List.of(),links);
        var aChanged=line("L037",List.of(new ResourceUse("compressor",2)));
        ProcessBlueprint modified=new ProcessBlueprint(id,2,10,List.of(aChanged,b,c,d,e,unrelated),
                List.of(asset),List.of(),links);
        var impact=BlueprintImpactAnalyzer.analyzeChange(baseline,modified);
        check(impact.affectedUnits().equals(Set.of("L037","L040","L043","L012","L055")),
                "Exact impacted IDs must match independent 5-line oracle");
        check(!impact.affectedUnits().contains("L099"),"Unrelated line must not appear");
        for(String impacted:impact.affectedUnits())check(impact.reasons().containsKey(impacted),"reason for "+impacted);
        check(impact.contention().size()==1 && impact.contention().get(0).configuredDemand()==4,"4 > 3 diagnosed");
        var equipmentChanged=new Equipment("E1","water-pump",1,List.of());
        var aMechanical=new ProcessUnit(a.id(),a.kind(),a.profileKey(),
                List.of(equipmentChanged),a.connections(),a.boundaries(),a.resources(),a.measurements(),false);
        ProcessBlueprint mechanical=new ProcessBlueprint(id,2,10,List.of(aMechanical,b,c,d,e,unrelated),
                List.of(asset),List.of(),links);
        var localImpact=BlueprintImpactAnalyzer.analyzeChange(baseline,mechanical);
        check(localImpact.affectedUnits().equals(Set.of("L037","L040","L043")),
                "Non-resource equipment change must not involve compressor peers");
        check(BlueprintDiff.compare(baseline,baseline).changedUnitIds().isEmpty(),"Identical snapshots yield empty diff");

        List<ProcessUnit> consumers=new ArrayList<>();
        for(int i=0;i<1000;i++)consumers.add(line("L"+i,List.of(new ResourceUse("hub",1))));
        var large=blueprint(UUID.randomUUID(),1,consumers,
                List.of(new SharedAsset("hub",2000,AssetMode.SHARED_CAPACITY)),List.of());
        check(BlueprintImpactAnalyzer.analyze(large,Set.of("L0")).affectedUnits().size()==1000,
                "1,000 consumers found without materializing pairwise edges");
        var overloaded=blueprint(UUID.randomUUID(),1,consumers.subList(0,10),
                List.of(new SharedAsset("hub",3,AssetMode.SHARED_CAPACITY)),List.of());
        var conflicts=ResourcePolicyValidator.inspect(overloaded);
        check(conflicts.size()==1 && conflicts.get(0).totalDemand()==10
                && conflicts.get(0).capacity()==3 && conflicts.get(0).consumers().size()==10,
                "Compressor demand, capacity and all users reported");
        check(BlueprintReadiness.blockers(overloaded,EquipmentCatalog.standard()).stream()
                .anyMatch(f->f.code().equals("RESOURCE_OVERCOMMIT")),
                "Overbooked shared resource blocks publication");

        var drafts=blueprint(UUID.randomUUID(),1,consumers.subList(0,99),
                List.of(new SharedAsset("hub",200,AssetMode.SHARED_CAPACITY)),List.of());
        check(BlueprintValidator.validate(drafts).stream().noneMatch(f->f.code().equals("LINE_POLICY_EXCEEDED")),
                "All 99 draft lines are allowed to exist");
        var ten=new TreeSet<String>();
        for(int i=0;i<10;i++)ten.add("L"+i);
        check(BlueprintActivationPolicy.validate(drafts,ten,10).isEmpty(),"Ten active lines permitted");
        ten.add("L10");
        check(BlueprintActivationPolicy.validate(drafts,ten,10).stream()
                .anyMatch(f->f.code().equals("ACTIVE_LINE_QUOTA_EXCEEDED")),"Eleventh active line denied");
        check(BlueprintActivationPolicy.validate(drafts,Set.of("FOREIGN"),10).stream()
                .anyMatch(f->f.code().equals("UNKNOWN_ACTIVE_UNIT")),"Unknown line rejected");
        // A DRAFT can contain 99 lines, while a strict publish preflight inspects only the selected 10.
        check(BlueprintPublicationGate.preflight(drafts,Set.of("L0","L1","L2","L3","L4","L5","L6","L7","L8","L9"),
                10,EquipmentCatalog.standard()).isEmpty(),"Ten selected simple active units qualify; other 89 remain DRAFT");
        check(BlueprintPublicationGate.preflight(drafts,Set.of("L0","L1","L2","L3","L4","L5","L6","L7","L8","L9","L10"),
                10,EquipmentCatalog.standard()).stream().anyMatch(f->f.code().equals("ACTIVE_LINE_QUOTA_EXCEEDED")),
                "Activation quota cannot be changed by Blueprint maxActiveLines");
        var crossDraft=new ProcessBlueprint(drafts.id(),drafts.revision(),drafts.maxActiveLines(),
                drafts.units(),drafts.sharedAssets(),List.of(),List.of(new Dependency("L0","L98")));
        check(BlueprintPublicationGate.preflight(crossDraft,Set.of("L0"),10,EquipmentCatalog.standard()).stream()
                .anyMatch(f->f.code().equals("INACTIVE_DEPENDENCY_PEER")),
                "Cannot activate a unit while required upstream unit remains inactive");

        var old=EquipmentCatalog.standard().type("water-pump");
        var v2=new EquipmentType(old.key(),2,old.label(),old.capabilities(),old.modes(),old.ports());
        var versioned=new EquipmentCatalog(List.of(old,v2),List.of());
        check(versioned.type("water-pump",1).equals(old),"Old version remains pinned");
        check(versioned.type("water-pump",2).equals(v2),"New version available");
        check(versioned.type("water-pump").version()==2,"Latest template selected for new equipment");
        check(EquipmentCatalog.standard().type("pressure-sensor").port("measurement").streamKind()
                ==EquipmentType.StreamKind.INSTRUMENT,"Pressure sensor no longer modeled as pipe");

        var port=new Port("out",Direction.OUT,FlowKind.MATERIAL,"water",0,1);
        var outlet=new Equipment("pump","water-pump",1,List.of(port));
        var bounds=List.of(new Boundary("out1",Direction.OUT,FlowKind.MATERIAL,"water","pump","out"),
                new Boundary("out2",Direction.OUT,FlowKind.MATERIAL,"water","pump","out"));
        var node=new ProcessUnit("P",UnitKind.STATION,"pump.v1",List.of(outlet),List.of(),bounds,List.of(),List.of(),false);
        var doubleBound=blueprint(UUID.randomUUID(),1,List.of(node),List.of(),List.of());
        check(BlueprintValidator.validate(doubleBound).stream()
                .anyMatch(f->f.code().equals("BOUNDARY_PORT_ALREADY_BOUND")),
                "One physical port cannot be bound twice");
        check(BlueprintValidator.validate(doubleBound).stream()
                .anyMatch(f->f.code().equals("PORT_CAPACITY")),
                "Physical port cardinality counts boundary bindings");
        var duplicateRes=new ProcessUnit("D",UnitKind.LINE,"test",List.of(),List.of(),List.of(),
                List.of(new ResourceUse("hub",1),new ResourceUse("hub",1)),List.of(),false);
        check(BlueprintValidator.validate(blueprint(UUID.randomUUID(),1,List.of(duplicateRes),
                List.of(new SharedAsset("hub",3,AssetMode.SHARED_CAPACITY)),List.of())).stream()
                .anyMatch(f->f.code().equals("DUPLICATE_RESOURCE_USE")),"Duplicate resource claim rejected");
        System.out.println("PASS "+assertions+" Blueprint hardening checks");
    }
}
