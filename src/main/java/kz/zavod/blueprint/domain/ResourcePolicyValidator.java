package kz.zavod.blueprint.domain;

import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

/**
 * Static concurrent-demand assumption used for publish qualification.
 * Time-multiplexed capacity requires explicit scheduling and separate qualification.
 */
public final class ResourcePolicyValidator {
    private ResourcePolicyValidator(){}
    public record Conflict(String code,String assetId,long totalDemand,int capacity,Set<String> consumers) {
        public Conflict { consumers=Set.copyOf(consumers); }
    }
    public static List<Conflict> inspect(ProcessBlueprint b) {
        Map<String,Long> totals=new HashMap<>();
        Map<String,Set<String>> users=new HashMap<>();
        for(ProcessUnit u:b.units())for(ResourceUse r:u.resources()) {
            totals.merge(r.sharedAssetId(),(long)r.demand(),Math::addExact);
            users.computeIfAbsent(r.sharedAssetId(),k->new TreeSet<>()).add(u.id());
        }
        List<Conflict> findings=new ArrayList<>();
        for(SharedAsset asset:b.sharedAssets()) {
            long total=totals.getOrDefault(asset.id(),0L);
            Set<String> peers=users.getOrDefault(asset.id(),Set.of());
            if(asset.mode()==AssetMode.EXCLUSIVE && peers.size()>1)
                findings.add(new Conflict("EXCLUSIVE_RESOURCE_CONFLICT",asset.id(),total,asset.capacity(),peers));
            if(total>asset.capacity())
                findings.add(new Conflict("RESOURCE_OVERCOMMIT",asset.id(),total,asset.capacity(),peers));
        }
        findings.sort(Comparator.comparing(Conflict::assetId).thenComparing(Conflict::code));
        return List.copyOf(findings);
    }
}
