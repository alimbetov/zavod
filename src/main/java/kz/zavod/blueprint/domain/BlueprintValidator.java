package kz.zavod.blueprint.domain;

import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Structural invariants, with separate DRAFT diagnostics and publication blockers. */
public final class BlueprintValidator {
    private BlueprintValidator() {}
    public enum Severity { ERROR, WARNING }
    public record Finding(String code,Severity severity,String entity) {}

    public static List<Finding> validate(ProcessBlueprint b) {
        List<Finding> errors = new ArrayList<>();
        if(b.units().isEmpty())errors.add(err("EMPTY_BLUEPRINT",b.id().toString()));
        // Number of DRAFT lines is unrestricted by activation policy.
        // Only the server-side activation service counts actually ACTIVE LINE instances.
        Map<String,ProcessUnit> units=new HashMap<>();
        Map<String,SharedAsset> assets=new HashMap<>();
        for(SharedAsset a:b.sharedAssets()) {
            if(a.capacity()<1 || a.mode()==null)errors.add(err("INVALID_ASSET",a.id()));
            if(assets.putIfAbsent(a.id(),a)!=null)errors.add(err("DUPLICATE_ASSET",a.id()));
        }
        Map<String,Integer> resourceUsers = new HashMap<>();
        for(ProcessUnit u:b.units()) {
            if(units.putIfAbsent(u.id(),u)!=null)errors.add(err("DUPLICATE_UNIT",u.id()));
            Map<String,Equipment> nodes=new HashMap<>();
            Map<String,Integer> links=new HashMap<>();
            Set<String> pairs=new HashSet<>();
            for(Equipment e:u.equipment()) {
                if(nodes.putIfAbsent(e.id(),e)!=null)errors.add(err("DUPLICATE_EQUIPMENT",u.id()+"/"+e.id()));
                Set<String> portIds=new HashSet<>();
                for(Port p:e.ports())if(!portIds.add(p.key()))errors.add(err("DUPLICATE_PORT",e.id()+"/"+p.key()));
            }
            List<Map.Entry<String,String>> edges=new ArrayList<>();
            for(Connection c:u.connections()) {
                Equipment source=nodes.get(c.sourceEquipment()),target=nodes.get(c.targetEquipment());
                if(source==null||target==null){errors.add(err("UNKNOWN_NODE",u.id()));continue;}
                Port sp=port(source,c.sourcePort()),tp=port(target,c.targetPort());
                if(sp==null||tp==null){errors.add(err("UNKNOWN_PORT",u.id()));continue;}
                if(sp.direction()!=Direction.OUT||tp.direction()!=Direction.IN)errors.add(err("INVALID_DIRECTION",u.id()));
                if(sp.kind()!=tp.kind()||!sp.medium().equals(tp.medium()))errors.add(err("INCOMPATIBLE_STREAM",u.id()));
                links.merge(source.id()+"/"+sp.key(),1,Integer::sum);
                links.merge(target.id()+"/"+tp.key(),1,Integer::sum);
                if(!pairs.add(source.id()+"/"+sp.key()+">"+target.id()+"/"+tp.key()))errors.add(err("DUPLICATE_CONNECTION",u.id()));
                edges.add(Map.entry(source.id(),target.id()));
            }
            boolean cyclicMaterial=cyclic(nodes.keySet(),edges);
            if(cyclicMaterial&&!u.recirculationAllowed())errors.add(err("MATERIAL_CYCLE",u.id()));
            if(u.recirculationAllowed()&&u.kind()!=UnitKind.CIRCUIT)errors.add(err("RECIRCULATION_NEEDS_CIRCUIT",u.id()));
            if(cyclicMaterial&&u.recirculationAllowed()&&u.kind()==UnitKind.CIRCUIT)
                errors.add(new Finding("RECIRCULATION_UNQUALIFIED",Severity.WARNING,u.id()));
            Set<String> boundaryKeys=new HashSet<>();
            Set<String> boundPhysicalPorts=new HashSet<>();
            for(Boundary boundary:u.boundaries()) {
                if(!boundaryKeys.add(boundary.key()))errors.add(err("DUPLICATE_BOUNDARY",u.id()+"/"+boundary.key()));
                if(boundary.equipmentId()==null||boundary.physicalPortKey()==null) {
                    errors.add(new Finding("UNBOUND_BOUNDARY",Severity.WARNING,u.id()+"/"+boundary.key()));
                } else {
                    Equipment equipment=nodes.get(boundary.equipmentId());
                    Port port=equipment==null?null:port(equipment,boundary.physicalPortKey());
                    if(port==null||port.direction()!=boundary.direction()||port.kind()!=boundary.kind()||!port.medium().equals(boundary.medium()))
                        errors.add(err("INVALID_BOUNDARY_BINDING",u.id()+"/"+boundary.key()));
                    else {
                        String boundKey=equipment.id()+"/"+port.key();
                        if(!boundPhysicalPorts.add(boundKey))
                            errors.add(err("BOUNDARY_PORT_ALREADY_BOUND",u.id()+"/"+boundary.key()));
                        links.merge(boundKey,1,Integer::sum);
                    }
                }
            }
            for(Equipment e:u.equipment())for(Port p:e.ports()) {
                int count=links.getOrDefault(e.id()+"/"+p.key(),0);
                if(count<p.min())errors.add(err("MISSING_REQUIRED_PORT",u.id()+"/"+e.id()+"/"+p.key()));
                if(count>p.max())errors.add(err("PORT_CAPACITY",u.id()+"/"+e.id()+"/"+p.key()));
            }
            Set<String> resourceIds=new HashSet<>();
            for(ResourceUse r:u.resources()) {
                if(!resourceIds.add(r.sharedAssetId()))errors.add(err("DUPLICATE_RESOURCE_USE",u.id()+"/"+r.sharedAssetId()));
                SharedAsset asset=assets.get(r.sharedAssetId());
                if(asset==null)errors.add(err("UNKNOWN_RESOURCE",u.id()+"/"+r.sharedAssetId()));
                else if(r.demand()<1||r.demand()>asset.capacity())errors.add(err("RESOURCE_CAPACITY",u.id()+"/"+r.sharedAssetId()));
                resourceUsers.merge(r.sharedAssetId(),1,Integer::sum);
            }
            for(Measurement m:u.measurements())if(!nodes.containsKey(m.sensorEquipmentId())||!nodes.containsKey(m.monitoredEquipmentId()))
                errors.add(err("UNKNOWN_MEASUREMENT_COMPONENT",u.id()));
        }
        for(SharedAsset a:b.sharedAssets())if(a.mode()==AssetMode.EXCLUSIVE&&resourceUsers.getOrDefault(a.id(),0)>1)
            errors.add(err("EXCLUSIVE_RESOURCE_CONFLICT",a.id()));
        Set<String> usedOut=new HashSet<>(),usedIn=new HashSet<>();
        List<Map.Entry<String,String>> dependencies=new ArrayList<>();
        for(Transfer t:b.transfers()) {
            ProcessUnit from=units.get(t.sourceUnit()),to=units.get(t.targetUnit());
            if(from==null||to==null){errors.add(err("UNKNOWN_TRANSFER_UNIT",t.targetUnit()));continue;}
            Boundary a=boundary(from,t.sourceBoundary()),c=boundary(to,t.targetBoundary());
            if(a==null||c==null){errors.add(err("UNKNOWN_BOUNDARY",t.targetUnit()));continue;}
            if(a.direction()!=Direction.OUT||c.direction()!=Direction.IN||a.kind()!=c.kind()||!a.medium().equals(c.medium()))
                errors.add(err("INCOMPATIBLE_TRANSFER",t.sourceUnit()+">"+t.targetUnit()));
            if(!usedOut.add(t.sourceUnit()+"/"+t.sourceBoundary()))errors.add(err("TRANSFER_FANOUT",t.sourceUnit()));
            if(!usedIn.add(t.targetUnit()+"/"+t.targetBoundary()))errors.add(err("TRANSFER_FANIN",t.targetUnit()));
            dependencies.add(Map.entry(from.id(),to.id()));
        }
        for(Dependency d:b.dependencies()) {
            if(!units.containsKey(d.sourceUnit())||!units.containsKey(d.targetUnit()))errors.add(err("UNKNOWN_DEPENDENCY_UNIT",d.targetUnit()));
            else dependencies.add(Map.entry(d.sourceUnit(),d.targetUnit()));
        }
        if(cyclic(units.keySet(),dependencies))errors.add(err("INTERUNIT_DEPENDENCY_CYCLE",b.id().toString()));
        return List.copyOf(errors);
    }
    private static Finding err(String code,String id){return new Finding(code,Severity.ERROR,id);}
    private static Port port(Equipment e,String key){return e.ports().stream().filter(p->p.key().equals(key)).findFirst().orElse(null);}
    private static Boundary boundary(ProcessUnit u,String key){return u.boundaries().stream().filter(p->p.key().equals(key)).findFirst().orElse(null);}
    private static boolean cyclic(Set<String> ids,List<Map.Entry<String,String>> edges) {
        Map<String,Integer> degree=new HashMap<>();Map<String,List<String>> outgoing=new HashMap<>();
        for(String id:ids){degree.put(id,0);outgoing.put(id,new ArrayList<>());}
        for(var edge:edges)if(degree.containsKey(edge.getKey())&&degree.containsKey(edge.getValue())) {
            outgoing.get(edge.getKey()).add(edge.getValue());degree.merge(edge.getValue(),1,Integer::sum);
        }
        Deque<String> queue=new ArrayDeque<>();degree.forEach((id,n)->{if(n==0)queue.add(id);});
        int seen=0;
        while(!queue.isEmpty()){String x=queue.remove();seen++;for(String y:outgoing.get(x))if(degree.merge(y,-1,Integer::sum)==0)queue.add(y);}
        return seen!=ids.size();
    }
}
