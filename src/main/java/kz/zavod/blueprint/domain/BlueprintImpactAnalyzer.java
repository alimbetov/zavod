package kz.zavod.blueprint.domain;

import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Conservative transitive impact analysis, never an automatic plant controller. */
public final class BlueprintImpactAnalyzer {
    private BlueprintImpactAnalyzer() {}
    public record AssetContention(String assetId, int configuredDemand, int capacity, boolean exclusiveConflict) {}
    public record Impact(Set<String> affectedUnits, List<AssetContention> contention) {
        public Impact { affectedUnits=Set.copyOf(affectedUnits);contention=List.copyOf(contention); }
    }

    public static Impact analyze(ProcessBlueprint b, Set<String> changedUnits) {
        Set<String> known = new HashSet<>();
        Map<String,Set<String>> users=new HashMap<>();
        Map<String,Integer> demand=new HashMap<>();
        for(ProcessUnit u:b.units()) {
            known.add(u.id());
            for(ResourceUse r:u.resources()) {
                users.computeIfAbsent(r.sharedAssetId(),k->new HashSet<>()).add(u.id());
                demand.merge(r.sharedAssetId(),r.demand(),Integer::sum);
            }
        }
        if(!known.containsAll(changedUnits))throw new IllegalArgumentException("Unknown changed unit");
        Map<String,Set<String>> outgoing=new HashMap<>();
        for(Transfer t:b.transfers())outgoing.computeIfAbsent(t.sourceUnit(),k->new HashSet<>()).add(t.targetUnit());
        for(Dependency d:b.dependencies())outgoing.computeIfAbsent(d.sourceUnit(),k->new HashSet<>()).add(d.targetUnit());
        // Changing a unit that uses a shared asset may affect every peer using that asset.
        for(Set<String> peers:users.values())for(String p:peers)outgoing.computeIfAbsent(p,k->new HashSet<>()).addAll(peers);
        Set<String> affected=new TreeSet<>(changedUnits);
        Deque<String> q=new ArrayDeque<>(changedUnits);
        while(!q.isEmpty())for(String next:outgoing.getOrDefault(q.remove(),Set.of()))if(affected.add(next))q.add(next);
        List<AssetContention> c=new ArrayList<>();
        for(SharedAsset asset:b.sharedAssets()) {
            int requested=demand.getOrDefault(asset.id(),0);
            int count=users.getOrDefault(asset.id(),Set.of()).size();
            boolean exclusive=asset.mode()==AssetMode.EXCLUSIVE&&count>1;
            if(requested>asset.capacity()||exclusive)c.add(new AssetContention(asset.id(),requested,asset.capacity(),exclusive));
        }
        c.sort(Comparator.comparing(AssetContention::assetId));
        return new Impact(affected,c);
    }
}
