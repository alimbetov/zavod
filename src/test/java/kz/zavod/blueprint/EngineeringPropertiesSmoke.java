package kz.zavod.blueprint;

import kz.zavod.catalog.*;
import kz.zavod.engineering.*;
import kz.zavod.blueprint.domain.*;
import java.math.BigDecimal;
import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Test-independent physical-dimension and catalogue oracle, run by javac smoke gate. */
public final class EngineeringPropertiesSmoke {
    private static int checks=0;
    private static void check(boolean condition,String why) {
        if(!condition)throw new AssertionError(why);
        checks++;
    }
    private static BigDecimal d(String s){return new BigDecimal(s);}
    private static void eq(String expected,BigDecimal actual,String why){
        check(d(expected).compareTo(actual)==0,why+" expected="+expected+" actual="+actual);
    }
    private static void rejects(Runnable action,String code) {
        try {action.run();} catch(IllegalArgumentException ex) {
            check(ex.getMessage().startsWith(code),"wrong code "+ex.getMessage());return;
        }
        throw new AssertionError("Expected rejection: "+code);
    }
    public static void main(String[] args){
        eq("1000",EngineeringUnits.convert(d("1"),"m3","L"),"volume");
        eq("1",EngineeringUnits.convert(d("1000"),"L","m3"),"volume back");
        eq("3.6",EngineeringUnits.convert(d("60"),"L/min","m3/h"),"volume flow");
        eq("100000",EngineeringUnits.convert(d("1"),"bar","Pa"),"pressure");
        eq("1",EngineeringUnits.convert(d("100000"),"Pa","bar"),"pressure back");
        eq("1000",EngineeringUnits.convert(d("1"),"kW","W"),"power");
        eq("1000",EngineeringUnits.convert(d("1"),"kWh","Wh"),"energy");
        eq("1000",EngineeringUnits.convert(d("1"),"t","kg"),"mass");
        eq("1000",EngineeringUnits.convert(d("1"),"t/h","kg/h"),"mass flow");
        eq("293.15",EngineeringUnits.convert(d("20"),"degC","K"),"temperature affine");
        eq("-10",EngineeringUnits.convert(d("263.15"),"K","°C"),"temperature below zero");
        eq("7.2",EngineeringUnits.convert(d("7.2"),"pH","pH"),"pH no fake dimension");
        rejects(()->EngineeringUnits.convert(d("3"),"kg","L"),"INCOMPATIBLE_UNIT_DIMENSION");
        rejects(()->EngineeringUnits.convert(d("3"),"m3/h","m3"),"INCOMPATIBLE_UNIT_DIMENSION");
        rejects(()->EngineeringUnits.convert(d("3"),"pH","mS/cm"),"INCOMPATIBLE_UNIT_DIMENSION");
        rejects(()->EngineeringUnits.convert(d("3"),"invalid","K"),"UNKNOWN_ENGINEERING_UNIT");

        var catalog=EquipmentCatalog.standard();
        var pump=catalog.type("water-pump",1);
        check(pump.properties().size()==3,"pump properties defined");
        var tank=catalog.type("water-tank");
        check(tank.properties().stream().anyMatch(p->p.key().equals("nominal_volume")),"tank volume defined");
        var manufacturer=new EngineeringPropertyValue("rated_flow",
            new EngineeringQuantity(d("600"),"L/min"),EngineeringPropertyValue.Source.MANUFACTURER,"OEM-X/page-3");
        check(EngineeringPropertyAudit.inspect(pump,List.of(manufacturer)).isEmpty(),"600 L/min valid pump property");
        var noEvidence=new EngineeringPropertyValue("rated_flow",new EngineeringQuantity(d("12"),"m3/h"),
            EngineeringPropertyValue.Source.MANUFACTURER,null);
        check(EngineeringPropertyAudit.inspect(pump,List.of(noEvidence)).stream()
            .anyMatch(f->f.code().equals("ENGINEERING_PROPERTY_EVIDENCE_REQUIRED")),"provenance required");
        var assumption=new EngineeringPropertyValue("rated_flow",new EngineeringQuantity(d("12"),"m3/h"),
            EngineeringPropertyValue.Source.PROJECT_ASSUMPTION,null);
        check(EngineeringPropertyAudit.inspect(pump,List.of(assumption)).stream()
            .anyMatch(f->f.code().equals("ENGINEERING_PROPERTY_NOT_VERIFIED")),"assumptions cannot publish");
        var incompatible=new EngineeringPropertyValue("rated_flow",new EngineeringQuantity(d("5"),"kg/h"),
            EngineeringPropertyValue.Source.ENGINEER_VERIFIED,"checked");
        check(EngineeringPropertyAudit.inspect(pump,List.of(incompatible)).stream()
            .anyMatch(f->f.code().equals("PROPERTY_UNIT_DIMENSION_MISMATCH")),"reject kg/h as pump flow");
        var negative=new EngineeringPropertyValue("rated_flow",new EngineeringQuantity(d("-1"),"m3/h"),
            EngineeringPropertyValue.Source.ENGINEER_VERIFIED,"checked");
        check(EngineeringPropertyAudit.inspect(pump,List.of(negative)).stream()
            .anyMatch(f->f.code().equals("ENGINEERING_PROPERTY_OUT_OF_RANGE")),"reject negative flow");
        check(EngineeringPropertyAudit.inspect(pump,List.of(manufacturer,manufacturer)).stream()
            .anyMatch(f->f.code().equals("DUPLICATE_ENGINEERING_PROPERTY")),"reject duplicate");
        var unknown=new EngineeringPropertyValue("invented_capacity",new EngineeringQuantity(d("10"),"m3/h"),
            EngineeringPropertyValue.Source.ENGINEER_VERIFIED,"checked");
        check(EngineeringPropertyAudit.inspect(pump,List.of(unknown)).stream()
            .anyMatch(f->f.code().equals("UNKNOWN_ENGINEERING_PROPERTY")),"unknown property rejected");

        var ports=pump.ports().stream().map(p->new Port(p.key(),Direction.valueOf(p.direction().name()),
            FlowKind.valueOf(p.streamKind().name()),p.medium(),p.minConnections(),p.maxConnections())).toList();
        var old=new Equipment("pump-1","water-pump",1,ports);
        var newVersion=new Equipment("pump-1","water-pump",1,ports,List.of(manufacturer));
        ProcessUnit unitA=new ProcessUnit("L037",UnitKind.LINE,"continuous.pump.v1",
            List.of(old),List.of(),List.of(),List.of(),List.of(),false);
        ProcessUnit unitB=new ProcessUnit("L037",UnitKind.LINE,"continuous.pump.v1",
            List.of(newVersion),List.of(),List.of(),List.of(),List.of(),false);
        UUID id=UUID.randomUUID();
        var before=new ProcessBlueprint(id,1,99,List.of(unitA),List.of(),List.of(),List.of());
        var after=new ProcessBlueprint(id,2,99,List.of(unitB),List.of(),List.of(),List.of());
        check(BlueprintDiff.compare(before,after).changedUnitIds().equals(Set.of("L037")),
              "parameter change detected in semantic diff");
        check(BlueprintCatalogAudit.inspect(after,catalog).isEmpty(),"property from catalogue validated");
        var unknownDraft=new ProcessBlueprint(id,2,99,
              List.of(new ProcessUnit("L037",UnitKind.LINE,"continuous.pump.v1",
                  List.of(new Equipment("pump-1","water-pump",1,ports,List.of(assumption))),
                  List.of(),List.of(),List.of(),List.of(),false)),
              List.of(),List.of(),List.of());
        check(BlueprintReadiness.blockers(unknownDraft,catalog).stream()
            .anyMatch(f->f.code().equals("ENGINEERING_PROPERTY_NOT_VERIFIED")),
            "assumption retained in draft but cannot be approved");
        check(BlueprintCatalogAudit.inspect(before,catalog).isEmpty(),"old 4-arg equipment remains valid");
        var bounded=new EngineeringPropertyDefinition("rated_temp",EngineeringUnits.Dimension.TEMPERATURE,
            "K",d("273.15"),d("373.15"),true,true);
        check(bounded.minInclusive().compareTo(d("273.15"))==0,"typed interval");
        rejects(()->new EngineeringPropertyDefinition("bad",EngineeringUnits.Dimension.MASS,
                 "m3",null,null,false,false),"INVALID_PROPERTY_DIMENSION");
        System.out.println("PASS "+checks+" Engineering Properties smoke checks");
    }
}
