package kz.zavod.lab;

import java.util.*;
import static kz.zavod.lab.PlantGraph.*;

/** Dependency-free smoke/regression runner; no Maven/Node/PLC required. */
public final class PlantScenarioLabTest {
    private static int assertions;
    public static void main(String[] args) {
        for(int n:new int[]{1,10,50,99}) {
            long begin=System.nanoTime();
            Plant p=PlantScenarios.generate(n,99);
            long generated=System.nanoTime();
            ok(PlantGraph.validate(p).isEmpty(),"Valid graph for "+n);
            long validated=System.nanoTime();
            ok(p.lines().size()==n,"Line count "+n);
            ok(p.lines().stream().map(Line::topologySignature).distinct().count()==n,"Unique topologies "+n);
            ok(p.lines().stream().map(l->l.profile()).distinct().count()==Math.min(n,3),"Process variety "+n);
            var a=VirtualPlantSimulator.run(p,240);
            long simulated=System.nanoTime();
            var b=VirtualPlantSimulator.run(p,240);
            ok(a.equals(b),"Deterministic virtual simulation "+n);
            ok(a.totalUnits()>0,"Progress "+n);
            ok(a.unitsByLine().values().stream().allMatch(v->v>0),"All lines progress "+n);
            ok(a.resourceWaits()>=0&&a.upstreamWaits()>=0,"No negative counts "+n);
            System.out.printf(Locale.ROOT,"lines=%d nodes=%d edges=%d transfers=%d dependencies=%d ticks=%d units=%d upstreamWait=%d resourceWait=%d idleTicks=%d generationMs=%.2f validationMs=%.2f simMs=%.2f%n",
                    n,p.lines().stream().mapToInt(l->l.nodes().size()).sum(),
                    p.lines().stream().mapToInt(l->l.edges().size()).sum(),p.transfers().size(),p.dependencies().size(),
                    a.ticks(),a.totalUnits(),a.upstreamWaits(),a.resourceWaits(),a.idleTicks(),
                    (generated-begin)/1_000_000.0,(validated-generated)/1_000_000.0,(simulated-validated)/1_000_000.0);
        }
        Plant p=PlantScenarios.generate(99,10);
        error(p,"LINE_LIMIT_EXCEEDED");
        Plant q=PlantScenarios.generate(10,10);
        ok(PlantGraph.validate(q).isEmpty(),"MVP 10 line policy");
        Plant graph=PlantScenarios.generate(3,99);
        Line first=graph.lines().get(0);
        List<Edge> edges=new ArrayList<>(first.edges());
        Edge valid=edges.get(0);
        edges.add(new Edge(valid.sourceId(),valid.sourcePort(),valid.targetId(),valid.targetPort()));
        Line duplicateEdge=new Line(first.id(),first.profile(),first.nodes(),edges,first.boundaries(),first.demands(),first.measurements());
        error(replaceLine(graph,duplicateEdge),"PORT_CAPACITY_EXCEEDED");
        List<Edge> missing=new ArrayList<>(first.edges());missing.remove(0);
        Line disconnected=new Line(first.id(),first.profile(),first.nodes(),missing,first.boundaries(),first.demands(),first.measurements());
        error(replaceLine(graph,disconnected),"REQUIRED_PORT_MISSING");
        Line cont=graph.lines().get(1);
        List<Edge> invalidStream=new ArrayList<>(cont.edges());
        Edge e=invalidStream.get(0);
        invalidStream.add(new Edge(e.sourceId(),e.sourcePort(),"outside-node","cement-in"));
        // Different line node references are not silently accepted in an intra-line graph.
        error(new Plant(graph.id(),99,List.of(first,new Line(cont.id(),cont.profile(),cont.nodes(),invalidStream,cont.boundaries(),cont.demands(),cont.measurements()),graph.lines().get(2)),graph.assets(),graph.transfers(),graph.dependencies()),"UNKNOWN_NODE");
        List<Transfer> badTransfer=new ArrayList<>(graph.transfers());
        badTransfer.add(new Transfer("L002","water-out","L003","bottle-in"));
        error(new Plant(graph.id(),99,graph.lines(),graph.assets(),badTransfer,graph.dependencies()),"INCOMPATIBLE_TRANSFER");
        List<Dependency> cyclic=new ArrayList<>(List.of(new Dependency("L001","L002"),new Dependency("L002","L001")));
        error(new Plant(graph.id(),99,graph.lines(),graph.assets(),graph.transfers(),cyclic),"INTERLINE_DEPENDENCY_CYCLE");
        List<SharedAsset> poorAssets=List.of(new SharedAsset("compressed-air",0),new SharedAsset("electrical-supply",4));
        error(new Plant(graph.id(),99,graph.lines(),poorAssets,graph.transfers(),graph.dependencies()),"INVALID_CAPACITY");
        Line badDemand=new Line(first.id(),first.profile(),first.nodes(),first.edges(),first.boundaries(),List.of(new ResourceDemand("compressed-air",4)),first.measurements());
        error(replaceLine(graph,badDemand),"RESOURCE_DEMAND_EXCEEDS_CAPACITY");
        Line continuous=graph.lines().get(1);
        ok(continuous.measurements().size()==1,"instrumentation modeled separately");
        ok(continuous.edges().stream().noneMatch(edge->continuous.nodes().stream().anyMatch(node->node.id().equals(edge.sourceId()) && node.typeKey().equals("pressure-sensor"))),"pressure sensor not material source");
        List<MeasurementAttachment> invalidMeasurement=List.of(new MeasurementAttachment("missing-sensor","N1","PRESSURE"));
        Line badSensor=new Line(continuous.id(),continuous.profile(),continuous.nodes(),continuous.edges(),continuous.boundaries(),continuous.demands(),invalidMeasurement);
        List<Line> withBadSensor=new ArrayList<>(graph.lines());withBadSensor.set(1,badSensor);
        error(new Plant(graph.id(),99,withBadSensor,graph.assets(),graph.transfers(),graph.dependencies()),"INVALID_MEASUREMENT_ATTACHMENT");
        Node cycleA=new Node("cycle-a","valve",List.of(new Port("in",Direction.IN,StreamKind.MATERIAL,"water",1,1),new Port("out",Direction.OUT,StreamKind.MATERIAL,"water",1,1)));
        Node cycleB=new Node("cycle-b","pump",List.of(new Port("in",Direction.IN,StreamKind.MATERIAL,"water",1,1),new Port("out",Direction.OUT,StreamKind.MATERIAL,"water",1,1)));
        Line cyclicMaterial=new Line("CYCLE",ProcessKind.CONTINUOUS,List.of(cycleA,cycleB),List.of(new Edge("cycle-a","out","cycle-b","in"),new Edge("cycle-b","out","cycle-a","in")),List.of(),List.of(),List.of());
        error(new Plant("negative-cycle",99,List.of(cyclicMaterial),List.of(),List.of(),List.of()),"MATERIAL_CYCLE");
        Node badInlet=new Node("bad-in","cement-inlet",List.of(new Port("in",Direction.IN,StreamKind.MATERIAL,"cement",1,1)));
        Node waterSource=new Node("water-src","water-source",List.of(new Port("out",Direction.OUT,StreamKind.MATERIAL,"water",1,1)));
        Line mixed=new Line("MIXED",ProcessKind.BATCH,List.of(waterSource,badInlet),List.of(new Edge("water-src","out","bad-in","in")),List.of(),List.of(),List.of());
        error(new Plant("negative-medium",99,List.of(mixed),List.of(),List.of(),List.of()),"INCOMPATIBLE_STREAM");
        Plant transferPlant=PlantScenarios.generate(7,99);
        List<Transfer> extraFanout=new ArrayList<>(transferPlant.transfers());
        extraFanout.add(new Transfer("L002","water-out","L007","water-supply"));
        error(new Plant(transferPlant.id(),99,transferPlant.lines(),transferPlant.assets(),extraFanout,transferPlant.dependencies()),"TRANSFER_FANOUT_UNSUPPORTED");
        ok(PlantScenarios.generate(99,99).transfers().size()>20,"Cross-line transfers exercised");
        ok(PlantScenarios.generate(99,99).dependencies().size()>10,"Cross-line precedence exercised");
        ok(PlantScenarios.generate(99,99).lines().stream().flatMap(l->l.demands().stream()).map(ResourceDemand::assetId).distinct().count()==2,"Shared assets exercised");
        rejects(()->PlantScenarios.generate(100,100),"Lab bound excludes 100 (not a product limit)");
        rejects(()->VirtualPlantSimulator.run(graph,0),"ticks positive");
        System.out.println("PASS "+assertions+" plant-scenario lab assertions");
    }
    private static Plant replaceLine(Plant p,Line line){List<Line> ls=new ArrayList<>(p.lines());ls.set(0,line);return new Plant(p.id(),p.maxActiveLines(),ls,p.assets(),p.transfers(),p.dependencies());}
    private static void error(Plant p,String code){ok(PlantGraph.validate(p).stream().anyMatch(f->f.code().equals(code)),"Expected finding "+code);}
    private static void rejects(Runnable r,String message){try{r.run();}catch(IllegalArgumentException ex){assertions++;return;}throw new AssertionError(message);}
    private static void ok(boolean test,String message){if(!test)throw new AssertionError(message);assertions++;}
}
