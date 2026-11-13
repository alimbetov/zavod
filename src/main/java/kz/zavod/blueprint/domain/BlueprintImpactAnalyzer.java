package kz.zavod.blueprint.domain;

import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/**
 * Conservative impact graph that never materializes the quadratic peer-to-peer
 * clique of consumers of a shared plant asset.
 */
public final class BlueprintImpactAnalyzer {
    private BlueprintImpactAnalyzer() {}
    public record AssetContention(String assetId, int configuredDemand, int capacity,
                                  boolean exclusiveConflict, Set<String> consumers) {
        public AssetContention { consumers=Set.copyOf(consumers); }
    }
    public record Impact(Set<String> affectedUnits, List<AssetContention> contention, Map<String,String> reasons) {
        public Impact {
            affectedUnits=Set.copyOf(affectedUnits);
            contention=List.copyOf(contention);
            reasons=Map.copyOf(reasons);
        }
    }

    /** Legacy conservative entry point: a changed unit might affect its shared assets. */
    public static Impact analyze(ProcessBlueprint b, Set<String> changedUnits) {
        return traverse(b,changedUnits,Set.of(),true);
    }

    /** Diff-aware entry point: physical changes propagate downstream; resource changes reach peers. */
    public static Impact analyzeChange(ProcessBlueprint before,ProcessBlueprint after) {
        BlueprintDiff.Delta delta=BlueprintDiff.compare(before,after);
        Set<String> origin=new TreeSet<>(delta.changedUnitIds());
        for(String asset:delta.changedAssetIds()) {
            for(ProcessUnit u:after.units())for(ResourceUse use:u.resources())if(use.sharedAssetId().equals(asset))
                origin.add(u.id());
        }
        return traverse(after,origin,delta.changedAssetIds(),false);
    }

    private static Impact traverse(ProcessBlueprint b,Set<String> changed,
                                   Set<String> changedAssets, boolean includeAllPeers) {
        Set<String> known=new HashSet<>();
        Map<String,SortedSet<String>> assetUsers=new HashMap<>();
        Map<String,Integer> totalDemand=new HashMap<>();
        Map<String,SortedSet<String>> downstream=new HashMap<>();
        for(ProcessUnit u:b.units()) {
            known.add(u.id());
            for(ResourceUse use:u.resources()) {
                assetUsers.computeIfAbsent(use.sharedAssetId(),k->new TreeSet<>()).add(u.id());
                totalDemand.merge(use.sharedAssetId(),use.demand(),Integer::sum);
            }
        }
        if(!known.containsAll(changed))throw new IllegalArgumentException("Unknown changed unit");
        for(Transfer t:b.transfers()) {
            downstream.computeIfAbsent(t.sourceUnit(),k->new TreeSet<>()).add(t.targetUnit());
        }
        for(Dependency d:b.dependencies()) {
            downstream.computeIfAbsent(d.sourceUnit(),k->new TreeSet<>()).add(d.targetUnit());
        }

        // Asset membership is indexed once. No peer-clique is created.
        Map<String,SortedSet<String>> ownedAssets=new HashMap<>();
        for(var e:assetUsers.entrySet())for(String owner:e.getValue())
            ownedAssets.computeIfAbsent(owner,k->new TreeSet<>()).add(e.getKey());

        Map<String,String> reasons=new TreeMap<>();
        Deque<String> queue=new ArrayDeque<>();
        for(String id:new TreeSet<>(changed))if(reasons.putIfAbsent(id,"CHANGED:"+id)==null)queue.add(id);
        Set<String> visitedAssets=new HashSet<>();
        while(!queue.isEmpty()) {
            String id=queue.remove();
            for(String next:downstream.getOrDefault(id,Collections.emptySortedSet())) {
                if(known.contains(next) && reasons.putIfAbsent(next,"DOWNSTREAM:"+id+"->"+next)==null)queue.add(next);
            }
            for(String asset:ownedAssets.getOrDefault(id,Collections.emptySortedSet())) {
                if(!includeAllPeers && !changedAssets.contains(asset))continue;
                if(!visitedAssets.add(asset))continue;
                for(String peer:assetUsers.getOrDefault(asset,Collections.emptySortedSet())) {
                    if(reasons.putIfAbsent(peer,"SHARED_ASSET:"+asset+":"+id+"->"+peer)==null)queue.add(peer);
                }
            }
        }
        List<AssetContention> contention=new ArrayList<>();
        for(SharedAsset asset:b.sharedAssets()) {
            int total=totalDemand.getOrDefault(asset.id(),0);
            Set<String> consumers=assetUsers.getOrDefault(asset.id(),Collections.emptySortedSet());
            boolean exclusive=asset.mode()==AssetMode.EXCLUSIVE&&consumers.size()>1;
            if(total>asset.capacity()||exclusive)
                contention.add(new AssetContention(asset.id(),total,asset.capacity(),exclusive,consumers));
        }
        contention.sort(Comparator.comparing(AssetContention::assetId));
        return new Impact(reasons.keySet(),contention,reasons);
    }
}
