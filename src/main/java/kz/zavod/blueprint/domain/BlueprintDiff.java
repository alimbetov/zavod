package kz.zavod.blueprint.domain;

import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Stable-ID semantic diff; ignores order-only changes and never compares UI canvas positions. */
public final class BlueprintDiff {
    private BlueprintDiff(){}
    public record Delta(Set<String> changedUnitIds,Set<String> changedAssetIds) {
        public Delta { changedUnitIds=Set.copyOf(changedUnitIds); changedAssetIds=Set.copyOf(changedAssetIds); }
    }
    public static Delta compare(ProcessBlueprint before,ProcessBlueprint after) {
        if(!before.id().equals(after.id()))throw new IllegalArgumentException("Different Blueprint IDs");
        Map<String,ProcessUnit> a=index(before.units()), b=index(after.units());
        Set<String> units=new TreeSet<>();
        Set<String> assets=new TreeSet<>();
        for(String id:a.keySet())if(!Objects.equals(a.get(id),b.get(id)))units.add(id);
        for(String id:b.keySet())if(!Objects.equals(a.get(id),b.get(id)))units.add(id);
        for(Transfer t:before.transfers())if(!after.transfers().contains(t)){units.add(t.sourceUnit());units.add(t.targetUnit());}
        for(Transfer t:after.transfers())if(!before.transfers().contains(t)){units.add(t.sourceUnit());units.add(t.targetUnit());}
        for(Dependency d:before.dependencies())if(!after.dependencies().contains(d)){units.add(d.sourceUnit());units.add(d.targetUnit());}
        for(Dependency d:after.dependencies())if(!before.dependencies().contains(d)){units.add(d.sourceUnit());units.add(d.targetUnit());}
        Map<String,SharedAsset> oldAssets=new HashMap<>(),newAssets=new HashMap<>();
        before.sharedAssets().forEach(x->oldAssets.put(x.id(),x));
        after.sharedAssets().forEach(x->newAssets.put(x.id(),x));
        Set<String> keys=new HashSet<>(oldAssets.keySet());keys.addAll(newAssets.keySet());
        for(String key:keys)if(!Objects.equals(oldAssets.get(key),newAssets.get(key)))assets.add(key);
        for(String unitId:units) {
            ProcessUnit old=a.get(unitId),next=b.get(unitId);
            if(old!=null)old.resources().forEach(r->assets.add(r.sharedAssetId()));
            if(next!=null)next.resources().forEach(r->assets.add(r.sharedAssetId()));
        }
        // Resource propagation is only triggered by resource claims/definitions,
        // not by a change to equipment topology or process profile.
        Set<String> actualResourceChanges=new TreeSet<>();
        for(String id:assets) {
            if(!Objects.equals(oldAssets.get(id),newAssets.get(id)))actualResourceChanges.add(id);
        }
        for(String unitId:units) {
            ProcessUnit old=a.get(unitId),next=b.get(unitId);
            if(old==null||next==null||!new HashSet<>(old.resources()).equals(new HashSet<>(next.resources()))) {
                if(old!=null)old.resources().forEach(r->actualResourceChanges.add(r.sharedAssetId()));
                if(next!=null)next.resources().forEach(r->actualResourceChanges.add(r.sharedAssetId()));
            }
        }
        return new Delta(units,actualResourceChanges);
    }
    private static Map<String,ProcessUnit> index(List<ProcessUnit> units) {
        Map<String,ProcessUnit> result=new HashMap<>();
        for(ProcessUnit unit:units)if(result.putIfAbsent(unit.id(),unit)!=null)
            throw new IllegalArgumentException("Duplicate unit in diff: "+unit.id());
        return result;
    }
}
