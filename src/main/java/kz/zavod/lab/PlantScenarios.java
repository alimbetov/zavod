package kz.zavod.lab;

import java.util.*;
import static kz.zavod.lab.PlantGraph.*;

/** Deterministic synthetic factory generator; generator range does not limit the product domain. */
public final class PlantScenarios {
    private PlantScenarios() {}

    private static Port port(String key,Direction dir,StreamKind kind,String medium,int min) {
        return new Port(key,dir,kind,medium,min,1);
    }
    private static final class Builder {
        final String id; final ProcessKind kind;
        final List<Node> nodes=new ArrayList<>();final List<Edge> edges=new ArrayList<>();
        final List<BoundaryPort> boundaries=new ArrayList<>();
        final List<ResourceDemand> demands=new ArrayList<>();
        final List<MeasurementAttachment> measurements=new ArrayList<>();
        int nextId=0;
        Builder(String id,ProcessKind kind){this.id=id;this.kind=kind;}
        String node(String type,StreamKind stream,String medium,boolean source,boolean sink) {
            String nodeId="N"+(++nextId);
            List<Port> ports=new ArrayList<>();
            if(!source)ports.add(port("in",Direction.IN,stream,medium,1));
            if(!sink)ports.add(port("out",Direction.OUT,stream,medium,1));
            nodes.add(new Node(nodeId,type,ports));return nodeId;
        }
        String mixer() {
            String nodeId="N"+(++nextId);
            nodes.add(new Node(nodeId,"batch-mixer",List.of(
                port("cement-in",Direction.IN,StreamKind.MATERIAL,"cement",1),
                port("aggregate-in",Direction.IN,StreamKind.MATERIAL,"aggregate",1),
                port("water-in",Direction.IN,StreamKind.MATERIAL,"water",1),
                port("admixture-in",Direction.IN,StreamKind.MATERIAL,"admixture",0),
                port("out",Direction.OUT,StreamKind.MATERIAL,"concrete",1)
            )));return nodeId;
        }
        void chain(List<String> types,StreamKind stream,String medium,String target,String targetPort) {
            String previous=null;
            for(String type:types){String current=node(type,stream,medium,previous==null,false);
                if(previous!=null)edges.add(new Edge(previous,"out",current,"in"));previous=current;}
            edges.add(new Edge(previous,"out",target,targetPort));
        }
        void chain(List<String> types,StreamKind stream,String medium) {
            String previous=null;
            for(int i=0;i<types.size();i++){
                String current=node(types.get(i),stream,medium,i==0,i==types.size()-1);
                if(previous!=null)edges.add(new Edge(previous,"out",current,"in"));previous=current;
            }
        }
        void pressureSensor(String monitoredId) {
            String nodeId="N"+(++nextId);
            nodes.add(new Node(nodeId,"pressure-sensor",List.of(port("measurement",Direction.IN,StreamKind.INSTRUMENT,"pressure",0))));
            measurements.add(new MeasurementAttachment(nodeId,monitoredId,"PRESSURE"));
        }
        Line build(){return new Line(id,kind,nodes,edges,boundaries,demands,measurements);}
    }
    private static void ifBit(List<String> list,int variation,int bit,String type) {if((variation&bit)!=0)list.add(type);}

    /** Each profile has 64 combinations (6 optional stations); 99-line plant consumes 33 per kind. */
    public static Line line(int number) {
        if(number<1)throw new IllegalArgumentException("Line number must be positive");
        String id=String.format(Locale.ROOT,"L%03d",number);
        int variation=(number-1)/3;
        int profile=(number-1)%3;
        if(profile==0){
            Builder b=new Builder(id,ProcessKind.BATCH);
            String mixer=b.mixer();
            List<String> cement=new ArrayList<>(List.of("cement-silo"));
            ifBit(cement,variation,1,"screw-feeder");
            ifBit(cement,variation,32,"cement-gate"); cement.add("cement-scale");
            b.chain(cement,StreamKind.MATERIAL,"cement",mixer,"cement-in");
            List<String> aggregate=new ArrayList<>(List.of("aggregate-bin","aggregate-scale"));
            ifBit(aggregate,variation,2,"belt-conveyor");
            ifBit(aggregate,variation,16,"aggregate-screen");
            b.chain(aggregate,StreamKind.MATERIAL,"aggregate",mixer,"aggregate-in");
            List<String> water=new ArrayList<>(List.of("water-tank","water-doser"));
            ifBit(water,variation,4,"water-flowmeter");
            b.chain(water,StreamKind.MATERIAL,"water",mixer,"water-in");
            if((variation&8)!=0)b.chain(List.of("admixture-tank","admixture-doser"),StreamKind.MATERIAL,"admixture",mixer,"admixture-in");
            String discharge=b.node("discharge",StreamKind.MATERIAL,"concrete",false,true);
            b.edges.add(new Edge(mixer,"out",discharge,"in"));
            b.boundaries.add(new BoundaryPort("water-supply",Direction.IN,StreamKind.MATERIAL,"water"));
            b.boundaries.add(new BoundaryPort("concrete-out",Direction.OUT,StreamKind.MATERIAL,"concrete"));
            b.demands.add(new ResourceDemand("compressed-air",1));
            return b.build();
        }
        if(profile==1){
            Builder b=new Builder(id,ProcessKind.CONTINUOUS);
            List<String> path=new ArrayList<>(List.of("process-tank"));
            ifBit(path,variation,1,"prefilter");path.add("water-pump");
            ifBit(path,variation,2,"flow-meter");ifBit(path,variation,4,"booster-pump");
            ifBit(path,variation,8,"check-valve");ifBit(path,variation,16,"uv-sterilizer");
            path.add("shutoff-valve");ifBit(path,variation,32,"pressure-regulator");path.add("water-outlet");
            b.chain(path,StreamKind.MATERIAL,"water");
            String pumpId=b.nodes.stream().filter(n->n.typeKey().equals("water-pump")).findFirst().orElseThrow().id();
            b.pressureSensor(pumpId);
            b.boundaries.add(new BoundaryPort("water-in",Direction.IN,StreamKind.MATERIAL,"water"));
            b.boundaries.add(new BoundaryPort("water-out",Direction.OUT,StreamKind.MATERIAL,"water"));
            b.demands.add(new ResourceDemand("electrical-supply",1));
            return b.build();
        }
        Builder b=new Builder(id,ProcessKind.DISCRETE);
        List<String> path=new ArrayList<>(List.of("bottle-infeed"));
        ifBit(path,variation,1,"bottle-unscrambler");ifBit(path,variation,2,"rinser");
        path.add("liquid-filler");path.add("capper");
        ifBit(path,variation,4,"batch-coder");path.add("labeler");
        ifBit(path,variation,8,"checkweigher");ifBit(path,variation,16,"vision-inspection");
        ifBit(path,variation,32,"cartoner");path.add("package-outlet");
        b.chain(path,StreamKind.ITEM_FLOW,"bottle");
        b.boundaries.add(new BoundaryPort("bottle-in",Direction.IN,StreamKind.ITEM_FLOW,"bottle"));
        b.boundaries.add(new BoundaryPort("bottle-out",Direction.OUT,StreamKind.ITEM_FLOW,"bottle"));
        b.demands.add(new ResourceDemand("compressed-air",1));
        return b.build();
    }

    /** Synthetic test policy 1..99; production maxActiveLines=10 remains configurable and separately tested. */
    public static Plant generate(int count,int policyMax) {
        if(count<1 || count>99)throw new IllegalArgumentException("Lab supports 1..99 lines");
        List<Line> lines=new ArrayList<>();
        for(int i=1;i<=count;i++)lines.add(line(i));
        List<SharedAsset> assets=List.of(new SharedAsset("compressed-air",3),new SharedAsset("electrical-supply",4));
        List<Transfer> transfers=new ArrayList<>();
        List<Dependency> deps=new ArrayList<>();
        for(int i=4;i<=count;i++) {
            if(i%3==1) transfers.add(new Transfer(id(i-2),"water-out",id(i),"water-supply"));
            else if(i%3==0)transfers.add(new Transfer(id(i-3),"bottle-out",id(i),"bottle-in"));
            if(i%7==0) deps.add(new Dependency(id(i-1),id(i)));
        }
        return new Plant("pilot-plant",policyMax,lines,assets,transfers,deps);
    }
    private static String id(int i){return String.format(Locale.ROOT,"L%03d",i);}
}
