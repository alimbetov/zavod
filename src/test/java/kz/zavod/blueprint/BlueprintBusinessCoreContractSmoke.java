package kz.zavod.blueprint;

import kz.zavod.blueprint.domain.*;
import kz.zavod.catalog.EquipmentCatalog;
import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Independent business-rule oracles: RED first, then fix the actual domain implementation. */
public final class BlueprintBusinessCoreContractSmoke {
    private static int assertions=0;
    private static void require(boolean correct,String reason) {
        if(!correct)throw new AssertionError(reason);
        assertions++;
    }
    private static ProcessBlueprint blueprint(List<ProcessUnit> units,List<SharedAsset> assets,
                                               List<Transfer> transfers,List<Dependency> dependencies) {
        return new ProcessBlueprint(UUID.randomUUID(),1,10,units,assets,transfers,dependencies);
    }
    private static ProcessUnit unit(String name,List<Equipment> equipment,List<Connection> connections,
                                    List<ResourceUse> resources,List<Measurement> measurements) {
        return new ProcessUnit(name,UnitKind.LINE,"continuous.pump.v1",
                equipment,connections,List.of(),resources,measurements,false);
    }
    private static Set<String> codes(ProcessBlueprint b) {
        Set<String> codes=new HashSet<>();
        for(BlueprintValidator.Finding f:BlueprintValidator.validate(b))codes.add(f.code());
        return codes;
    }
    private static Equipment portEquipment(String id,FlowKind kind) {
        return new Equipment(id,"test",1,List.of(
                new Port("in",Direction.IN,kind,"signal",1,1),
                new Port("out",Direction.OUT,kind,"signal",1,1)));
    }
    private static void independentFlowCycleRules() {
        var instrument=List.of(portEquipment("A",FlowKind.INSTRUMENT),
                               portEquipment("B",FlowKind.INSTRUMENT));
        var links=List.of(new Connection("A","out","B","in"),new Connection("B","out","A","in"));
        Set<String> instrumentCodes=codes(blueprint(
                List.of(unit("signals",instrument,links,List.of(),List.of())),
                List.of(),List.of(),List.of()));
        require(!instrumentCodes.contains("MATERIAL_CYCLE"),
                "BR-025: instrumentation feedback is not a material cycle: "+instrumentCodes);
        require(!instrumentCodes.contains("ITEM_FLOW_CYCLE"),
                "BR-025: signal graph must not be mistaken for discrete item loop");

        var items=List.of(portEquipment("A",FlowKind.ITEM_FLOW),
                          portEquipment("B",FlowKind.ITEM_FLOW));
        Set<String> itemCodes=codes(blueprint(
                List.of(unit("packaging",items,links,List.of(),List.of())),
                List.of(),List.of(),List.of()));
        require(itemCodes.contains("ITEM_FLOW_CYCLE"),
                "BR-025: discrete material handling loop requires explicit qualification: "+itemCodes);
    }
    private static void measurementSourceMustBeQualified() {
        var catalog=EquipmentCatalog.standard();
        var tank=new Equipment("tank","water-tank",1,List.of(
                new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
        var pump=new Equipment("pump","water-pump",1,List.of(
                new Port("water-in",Direction.IN,FlowKind.MATERIAL,"water",1,1),
                new Port("water-out",Direction.OUT,FlowKind.MATERIAL,"water",0,1)));
        var invalid=blueprint(List.of(unit("plant",List.of(tank,pump),
                List.of(new Connection("tank","water-out","pump","water-in")),List.of(),
                List.of(new Measurement("tank","pump","pressure")))),
                List.of(),List.of(),List.of());
        require(BlueprintCatalogAudit.inspect(invalid,catalog).stream()
                .anyMatch(f->f.code().equals("INVALID_MEASUREMENT_SENSOR")),
                "BR-026: a storage tank cannot impersonate a measurement-capable sensor");
        require(BlueprintReadiness.blockers(invalid,catalog).stream()
                .anyMatch(f->f.code().equals("INVALID_MEASUREMENT_SENSOR")),
                "BR-026: publication readiness must reject fake sensor");
        var pressureSensor=new Equipment("sensor","pressure-sensor",1,List.of(
                new Port("measurement",Direction.IN,FlowKind.INSTRUMENT,"pressure",0,1)));
        var valid=blueprint(List.of(unit("plant",List.of(tank,pump,pressureSensor),
                List.of(new Connection("tank","water-out","pump","water-in")),List.of(),
                List.of(new Measurement("sensor","pump","pressure")))),
                List.of(),List.of(),List.of());
        require(BlueprintCatalogAudit.inspect(valid,catalog).stream()
                .noneMatch(f->f.code().equals("INVALID_MEASUREMENT_SENSOR")),
                "BR-026: real measurement template must remain usable");
        var blank=blueprint(List.of(unit("plant",List.of(tank,pump,pressureSensor),
                List.of(new Connection("tank","water-out","pump","water-in")),List.of(),
                List.of(new Measurement("sensor","pump","  ")))),List.of(),List.of(),List.of());
        require(BlueprintCatalogAudit.inspect(blank,catalog).stream()
                .anyMatch(f->f.code().equals("INVALID_MEASUREMENT_SIGNAL")),
                "BR-026: anonymous signal key must be rejected");
    }
    private static void impactReportsOnlyCurrentConflicts() {
        UUID id=UUID.randomUUID();
        var asset=new SharedAsset("compressor",3,AssetMode.SHARED_CAPACITY);
        var a=unit("A",List.of(),List.of(),List.of(new ResourceUse("compressor",2)),List.of());
        var b=unit("B",List.of(),List.of(),List.of(new ResourceUse("compressor",2)),List.of());
        var c=unit("C",List.of(),List.of(),List.of(),List.of());
        var before=new ProcessBlueprint(id,1,10,List.of(a,b,c),List.of(asset),List.of(),List.of());
        var after=new ProcessBlueprint(id,2,10,List.of(a,c),List.of(asset),List.of(),List.of());
        require(!ResourcePolicyValidator.inspect(before).isEmpty(),"Golden baseline is overloaded");
        require(ResourcePolicyValidator.inspect(after).isEmpty(),"Golden target is not overloaded");
        var impact=BlueprintImpactAnalyzer.analyzeChange(before,after);
        require(impact.contention().isEmpty(),
                "BR-024: resolved historic overcommit must not appear as current conflict: "+impact.contention());
        require(impact.affectedUnits().equals(Set.of("A","B")),
                "BR-024: target impact includes removed B and remaining compressor consumer A, excludes C: "+impact.affectedUnits());
    }
    private static void selectedPreflightCannotHidePhysicalDependencies() {
        var a=unit("A",List.of(),List.of(),List.of(),List.of());
        var b=unit("B",List.of(),List.of(),List.of(),List.of());
        var bp=blueprint(List.of(a,b),List.of(),List.of(),List.of(new Dependency("A","B")));
        var failures=BlueprintPublicationGate.preflight(bp,Set.of("A"),10,EquipmentCatalog.standard());
        require(failures.stream().anyMatch(f->f.code().equals("INACTIVE_DEPENDENCY_PEER")),
                "BR-008: omitted peer in dependency must still block activation");
        require(BlueprintPublicationGate.preflight(bp,Set.of("A","B"),10,
                EquipmentCatalog.standard()).isEmpty(),"BR-008: complete physical dependency selection passes");
    }
    public static void main(String[] args) {
        independentFlowCycleRules();
        measurementSourceMustBeQualified();
        impactReportsOnlyCurrentConflicts();
        selectedPreflightCannotHidePhysicalDependencies();
        System.out.println("PASS "+assertions+" independent Blueprint business core assertions");
    }
}
