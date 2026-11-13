package kz.zavod.lab;

import java.util.*;

/** Pure engineering graph for a virtual-plant experiment; does NOT access physical PLCs. */
public final class PlantGraph {
    private PlantGraph() {}

    public enum ProcessKind { BATCH, CONTINUOUS, DISCRETE }
    public enum StreamKind { MATERIAL, ITEM_FLOW, INSTRUMENT }
    public enum Direction { IN, OUT }

    public record Port(String key, Direction direction, StreamKind kind, String medium, int minLinks, int maxLinks) {
        public Port {
            if (key == null || key.isBlank() || medium == null || medium.isBlank()) throw new IllegalArgumentException("Invalid port key/medium");
            Objects.requireNonNull(direction);
            Objects.requireNonNull(kind);
            if (minLinks < 0 || maxLinks < 1 || maxLinks < minLinks) throw new IllegalArgumentException("Invalid cardinality");
        }
    }
    public record Node(String id, String typeKey, List<Port> ports) {
        public Node { ports = List.copyOf(ports); }
        public Port port(String key) { return ports.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null); }
    }
    public record Edge(String sourceId, String sourcePort, String targetId, String targetPort) {}
    public record BoundaryPort(String key, Direction direction, StreamKind kind, String medium) {}
    public record ResourceDemand(String assetId, int units) {}
    public record MeasurementAttachment(String sensorId, String monitoredNodeId, String signalKey) {}
    public record SharedAsset(String id, int capacityPerTick) {}
    public record Line(String id, ProcessKind profile, List<Node> nodes, List<Edge> edges,
                       List<BoundaryPort> boundaries, List<ResourceDemand> demands, List<MeasurementAttachment> measurements) {
        public Line { nodes=List.copyOf(nodes); edges=List.copyOf(edges); boundaries=List.copyOf(boundaries); demands=List.copyOf(demands); measurements=List.copyOf(measurements); }
        public BoundaryPort boundary(String key) { return boundaries.stream().filter(b->b.key().equals(key)).findFirst().orElse(null); }
        /** Structural signature excludes line/node IDs and includes equipment order and wiring. */
        public String topologySignature() {
            Map<String,Integer> indices=new HashMap<>();
            for(int i=0;i<nodes.size();i++) indices.put(nodes.get(i).id(),i);
            List<String> wiring=new ArrayList<>();
            for(Edge e:edges) wiring.add(indices.get(e.sourceId())+"."+e.sourcePort()+">"+indices.get(e.targetId())+"."+e.targetPort());
            Collections.sort(wiring);
            List<String> measurementShape=measurements.stream().map(m->indices.get(m.sensorId())+">"+indices.get(m.monitoredNodeId())+":"+m.signalKey()).sorted().toList();
            return profile+"|"+nodes.stream().map(Node::typeKey).toList()+"|"+wiring+"|"+measurementShape;
        }
    }
    /** Interline availability token, not an engineered pipe, meter or mass balance. */
    public record Transfer(String fromLine, String fromPort, String toLine, String toPort) {}
    /** A release dependency; cyclic dependencies are rejected in this lab. */
    public record Dependency(String fromLine, String toLine) {}
    public record Plant(String id, int maxActiveLines, List<Line> lines, List<SharedAsset> assets,
                        List<Transfer> transfers, List<Dependency> dependencies) {
        public Plant {
            lines=List.copyOf(lines); assets=List.copyOf(assets);
            transfers=List.copyOf(transfers); dependencies=List.copyOf(dependencies);
        }
    }
    public record Finding(String code, String entity) {}

    public static List<Finding> validate(Plant plant) {
        List<Finding> errors=new ArrayList<>();
        if(plant.lines().isEmpty()) errors.add(new Finding("NO_ACTIVE_LINES",plant.id()));
        if(plant.lines().size()>plant.maxActiveLines()) errors.add(new Finding("LINE_LIMIT_EXCEEDED",plant.id()));
        Map<String,Line> lines=new HashMap<>();
        for(Line l:plant.lines()) if(lines.putIfAbsent(l.id(),l)!=null) errors.add(new Finding("DUPLICATE_LINE",l.id()));
        Map<String,SharedAsset> assets=new HashMap<>();
        for(SharedAsset a:plant.assets()) {
            if(a.capacityPerTick()<1) errors.add(new Finding("INVALID_CAPACITY",a.id()));
            if(assets.putIfAbsent(a.id(),a)!=null) errors.add(new Finding("DUPLICATE_ASSET",a.id()));
        }
        for(Line l:plant.lines()) {
            Map<String,Node> nodes=new HashMap<>();
            Map<String,Integer> occupancy=new HashMap<>();
            for(Node n:l.nodes()) {
                if(nodes.putIfAbsent(n.id(),n)!=null) errors.add(new Finding("DUPLICATE_NODE",l.id()+"/"+n.id()));
                Set<String> portKeys=new HashSet<>();
                for(Port p:n.ports()) if(!portKeys.add(p.key())) errors.add(new Finding("DUPLICATE_PORT",n.id()+"/"+p.key()));
            }
            for(Edge e:l.edges()) {
                Node from=nodes.get(e.sourceId()),to=nodes.get(e.targetId());
                if(from==null || to==null) { errors.add(new Finding("UNKNOWN_NODE",l.id())); continue; }
                Port a=from.port(e.sourcePort()),b=to.port(e.targetPort());
                if(a==null || b==null) { errors.add(new Finding("UNKNOWN_PORT",l.id())); continue; }
                if(a.direction()!=Direction.OUT || b.direction()!=Direction.IN) errors.add(new Finding("INVALID_DIRECTION",l.id()));
                if(a.kind()!=b.kind() || !a.medium().equals(b.medium())) errors.add(new Finding("INCOMPATIBLE_STREAM",l.id()));
                occupancy.merge(e.sourceId()+"/"+e.sourcePort(),1,Integer::sum);
                occupancy.merge(e.targetId()+"/"+e.targetPort(),1,Integer::sum);
            }
            for(Node n:l.nodes()) for(Port p:n.ports()) {
                int used=occupancy.getOrDefault(n.id()+"/"+p.key(),0);
                if(used<p.minLinks()) errors.add(new Finding("REQUIRED_PORT_MISSING",l.id()+"/"+n.id()+"/"+p.key()));
                if(used>p.maxLinks()) errors.add(new Finding("PORT_CAPACITY_EXCEEDED",l.id()+"/"+n.id()+"/"+p.key()));
            }
            if(cyclic(nodes.keySet(),l.edges().stream().filter(e->nodes.containsKey(e.sourceId())&&nodes.containsKey(e.targetId()))
                    .map(e->Map.entry(e.sourceId(),e.targetId())).toList())) errors.add(new Finding("MATERIAL_CYCLE",l.id()));
            for(MeasurementAttachment attachment:l.measurements()) {
                Node sensor=nodes.get(attachment.sensorId()),target=nodes.get(attachment.monitoredNodeId());
                if(sensor==null||target==null||sensor.port("measurement")==null
                    ||sensor.port("measurement").kind()!=StreamKind.INSTRUMENT)
                    errors.add(new Finding("INVALID_MEASUREMENT_ATTACHMENT",l.id()));
            }
            Set<String> boundaryKeys=new HashSet<>();
            for(BoundaryPort p:l.boundaries()) if(!boundaryKeys.add(p.key())) errors.add(new Finding("DUPLICATE_BOUNDARY_PORT",l.id()));
            Set<String> usedResources=new HashSet<>();
            for(ResourceDemand demand:l.demands()) {
                if(!usedResources.add(demand.assetId())) errors.add(new Finding("DUPLICATE_RESOURCE_DEMAND",l.id()));
                if(!assets.containsKey(demand.assetId())) errors.add(new Finding("UNKNOWN_RESOURCE",l.id()));
                else if(demand.units()<1 || demand.units()>assets.get(demand.assetId()).capacityPerTick())
                    errors.add(new Finding("RESOURCE_DEMAND_EXCEEDS_CAPACITY",l.id()));
            }
        }
        Set<String> outPorts=new HashSet<>(),inPorts=new HashSet<>();
        for(Transfer t:plant.transfers()) {
            Line from=lines.get(t.fromLine()),to=lines.get(t.toLine());
            if(from==null || to==null || from.id().equals(to.id())) {errors.add(new Finding("INVALID_TRANSFER_LINE",t.toLine()));continue;}
            BoundaryPort a=from.boundary(t.fromPort()),b=to.boundary(t.toPort());
            if(a==null||b==null) {errors.add(new Finding("UNKNOWN_BOUNDARY",t.toLine()));continue;}
            if(a.direction()!=Direction.OUT||b.direction()!=Direction.IN||a.kind()!=b.kind()||!a.medium().equals(b.medium()))
                errors.add(new Finding("INCOMPATIBLE_TRANSFER",t.toLine()));
            if(!outPorts.add(t.fromLine()+"/"+t.fromPort())) errors.add(new Finding("TRANSFER_FANOUT_UNSUPPORTED",t.fromLine()));
            if(!inPorts.add(t.toLine()+"/"+t.toPort())) errors.add(new Finding("TRANSFER_FANIN_UNSUPPORTED",t.toLine()));
        }
        List<Map.Entry<String,String>> deps=new ArrayList<>();
        for(Dependency d:plant.dependencies()) {
            if(!lines.containsKey(d.fromLine())||!lines.containsKey(d.toLine())) errors.add(new Finding("UNKNOWN_DEPENDENCY_LINE",d.toLine()));
            else deps.add(Map.entry(d.fromLine(),d.toLine()));
        }
        for(Transfer t:plant.transfers()) if(lines.containsKey(t.fromLine())&&lines.containsKey(t.toLine())) deps.add(Map.entry(t.fromLine(),t.toLine()));
        if(cyclic(lines.keySet(),deps)) errors.add(new Finding("INTERLINE_DEPENDENCY_CYCLE",plant.id()));
        return List.copyOf(errors);
    }

    private static boolean cyclic(Set<String> ids,List<Map.Entry<String,String>> links) {
        Map<String,Integer> degree=new HashMap<>();
        Map<String,List<String>> outgoing=new HashMap<>();
        for(String id:ids){degree.put(id,0);outgoing.put(id,new ArrayList<>());}
        for(var link:links) if(degree.containsKey(link.getKey())&&degree.containsKey(link.getValue())) {
            outgoing.get(link.getKey()).add(link.getValue());degree.merge(link.getValue(),1,Integer::sum);
        }
        ArrayDeque<String> q=new ArrayDeque<>();
        degree.forEach((id,n)->{if(n==0)q.add(id);});
        int visited=0;
        while(!q.isEmpty()) {
            String id=q.remove();visited++;
            for(String target:outgoing.get(id)) if(degree.merge(target,-1,Integer::sum)==0) q.add(target);
        }
        return visited!=ids.size();
    }
}
