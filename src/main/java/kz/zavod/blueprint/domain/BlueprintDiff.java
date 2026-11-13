package kz.zavod.blueprint.domain;

import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Identity-based semantic diff; ordering of independent graph elements is not an engineering change. */
public final class BlueprintDiff {
    private BlueprintDiff(){}
    public record Delta(Set<String> changedUnitIds,Set<String> changedAssetIds) {
        public Delta { changedUnitIds=Set.copyOf(changedUnitIds);changedAssetIds=Set.copyOf(changedAssetIds); }
    }
    public static Delta compare(ProcessBlueprint before,ProcessBlueprint after) {
        if(!before.id().equals(after.id()))throw new IllegalArgumentException("Different Blueprint IDs");
        Map<String,ProcessUnit> a=index(before.units()), b=index(after.units());
        Set<String> units=new TreeSet<>();
        Set<String> keys=new HashSet<>(a.keySet());keys.addAll(b.keySet());
        for(String id:keys)if(!equivalent(a.get(id),b.get(id)))units.add(id);
        Set<Transfer> removedTransfers=new HashSet<>(before.transfers());
        removedTransfers.removeAll(after.transfers());
        Set<Transfer> addedTransfers=new HashSet<>(after.transfers());
        addedTransfers.removeAll(before.transfers());
        for(Transfer t:removedTransfers){units.add(t.sourceUnit());units.add(t.targetUnit());}
        for(Transfer t:addedTransfers){units.add(t.sourceUnit());units.add(t.targetUnit());}
        Set<Dependency> removedDependencies=new HashSet<>(before.dependencies());
        removedDependencies.removeAll(after.dependencies());
        Set<Dependency> addedDependencies=new HashSet<>(after.dependencies());
        addedDependencies.removeAll(before.dependencies());
        for(Dependency d:removedDependencies){units.add(d.sourceUnit());units.add(d.targetUnit());}
        for(Dependency d:addedDependencies){units.add(d.sourceUnit());units.add(d.targetUnit());}

        Map<String,SharedAsset> oldAssets=new HashMap<>(),newAssets=new HashMap<>();
        before.sharedAssets().forEach(x->oldAssets.put(x.id(),x));
        after.sharedAssets().forEach(x->newAssets.put(x.id(),x));
        Set<String> assetKeys=new HashSet<>(oldAssets.keySet());assetKeys.addAll(newAssets.keySet());
        Set<String> resourceChanges=new TreeSet<>();
        for(String key:assetKeys)if(!Objects.equals(oldAssets.get(key),newAssets.get(key)))
            resourceChanges.add(key);
        for(String id:units) {
            ProcessUnit old=a.get(id),next=b.get(id);
            if(old==null||next==null||!new HashSet<>(old.resources()).equals(new HashSet<>(next.resources()))) {
                if(old!=null)old.resources().forEach(r->resourceChanges.add(r.sharedAssetId()));
                if(next!=null)next.resources().forEach(r->resourceChanges.add(r.sharedAssetId()));
            }
        }
        return new Delta(units,resourceChanges);
    }
    private static boolean equivalent(ProcessUnit a,ProcessUnit b) {
        if(a==null||b==null)return a==b;
        return a.kind()==b.kind() && Objects.equals(a.profileKey(),b.profileKey())
            && a.recirculationAllowed()==b.recirculationAllowed()
            && equipmentMap(a.equipment()).equals(equipmentMap(b.equipment()))
            && new HashSet<>(a.connections()).equals(new HashSet<>(b.connections()))
            && boundaryMap(a.boundaries()).equals(boundaryMap(b.boundaries()))
            && new HashSet<>(a.resources()).equals(new HashSet<>(b.resources()))
            && new HashSet<>(a.measurements()).equals(new HashSet<>(b.measurements()));
    }
    private static Map<String,Equipment> equipmentMap(List<Equipment> list) {
        Map<String,Equipment> m=new HashMap<>();
        for(Equipment e:list) {
            // Physical ports are addressed by stable keys. Presentation order is not topology.
            List<Port> ports=new ArrayList<>(e.ports());
            ports.sort(Comparator.comparing(Port::key));
            Equipment canonical=new Equipment(e.id(),e.typeKey(),e.templateVersion(),ports);
            if(m.putIfAbsent(e.id(),canonical)!=null)
                throw new IllegalArgumentException("Duplicate equipment in diff: "+e.id());
        }
        return m;
    }
    private static Map<String,Boundary> boundaryMap(List<Boundary> list) {
        Map<String,Boundary> m=new HashMap<>();
        for(Boundary b:list)if(m.putIfAbsent(b.key(),b)!=null)
            throw new IllegalArgumentException("Duplicate boundary in diff: "+b.key());
        return m;
    }
    private static Map<String,ProcessUnit> index(List<ProcessUnit> units) {
        Map<String,ProcessUnit> result=new HashMap<>();
        for(ProcessUnit unit:units)if(result.putIfAbsent(unit.id(),unit)!=null)
            throw new IllegalArgumentException("Duplicate unit in diff: "+unit.id());
        return result;
    }
}
