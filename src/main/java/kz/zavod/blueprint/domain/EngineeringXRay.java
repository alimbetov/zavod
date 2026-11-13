package kz.zavod.blueprint.domain;

import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/** Structural dependency projection; never claims actual equipment operating state. */
public final class EngineeringXRay {
    private EngineeringXRay() {}
    public record View(String unitId,Set<String> directUpstream,Set<String> directDownstream,
                       Set<String> upstream,Set<String> downstream,Set<String> sharedResourcePeers,
                       Set<String> sharedAssets) {
        public View {
            directUpstream=Set.copyOf(directUpstream);directDownstream=Set.copyOf(directDownstream);
            upstream=Set.copyOf(upstream);downstream=Set.copyOf(downstream);
            sharedResourcePeers=Set.copyOf(sharedResourcePeers);sharedAssets=Set.copyOf(sharedAssets);
        }
    }
    public static View inspect(ProcessBlueprint blueprint,String unitId) {
        Objects.requireNonNull(blueprint);
        if(unitId==null||unitId.isBlank())throw new IllegalArgumentException("UNKNOWN_XRAY_UNIT");
        Map<String,ProcessUnit> units=new HashMap<>();
        blueprint.units().forEach(u->units.put(u.id(),u));
        var selected=units.get(unitId);
        if(selected==null)throw new IllegalArgumentException("UNKNOWN_XRAY_UNIT");
        Map<String,Set<String>> next=new HashMap<>(),prev=new HashMap<>();
        for(String id:units.keySet()){next.put(id,new TreeSet<>());prev.put(id,new TreeSet<>());}
        for(Transfer t:blueprint.transfers())link(t.sourceUnit(),t.targetUnit(),next,prev);
        for(Dependency d:blueprint.dependencies())link(d.sourceUnit(),d.targetUnit(),next,prev);
        Set<String> assets=new TreeSet<>();
        for(ResourceUse use:selected.resources())assets.add(use.sharedAssetId());
        Set<String> peers=new TreeSet<>();
        for(ProcessUnit u:blueprint.units())
            if(!u.id().equals(unitId)&&u.resources().stream().anyMatch(x->assets.contains(x.sharedAssetId())))
                peers.add(u.id());
        return new View(unitId,prev.get(unitId),next.get(unitId),closure(unitId,prev),
                closure(unitId,next),peers,assets);
    }
    private static void link(String a,String b,Map<String,Set<String>> next,Map<String,Set<String>> prev) {
        if(next.containsKey(a)&&prev.containsKey(b)){next.get(a).add(b);prev.get(b).add(a);}
    }
    private static Set<String> closure(String id,Map<String,Set<String>> links) {
        Set<String> seen=new TreeSet<>();
        Deque<String> queue=new ArrayDeque<>(links.getOrDefault(id,Set.of()));
        while(!queue.isEmpty()) {
            String x=queue.removeFirst();
            if(x.equals(id)||!seen.add(x))continue;
            queue.addAll(links.getOrDefault(x,Set.of()));
        }
        return seen;
    }
}
