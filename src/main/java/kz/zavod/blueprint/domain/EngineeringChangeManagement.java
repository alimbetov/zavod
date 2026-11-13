package kz.zavod.blueprint.domain;

import java.util.*;

/**
 * Deterministic comparison of immutable revisions. Actor/reason are explicitly
 * unknown unless stored by an independent trustworthy audit system.
 */
public final class EngineeringChangeManagement {
    private EngineeringChangeManagement(){}
    public record Review(long fromRevision,long toRevision,Set<String> changedUnits,
                         Set<String> changedAssets,Set<String> affectedUnits,
                         Map<String,String> impactReasons,String changedBy,String changeReason,
                         boolean canRestoreWithoutApproval) {
        public Review {
            changedUnits=Set.copyOf(changedUnits);
            changedAssets=Set.copyOf(changedAssets);
            affectedUnits=Set.copyOf(affectedUnits);
            impactReasons=Map.copyOf(impactReasons);
        }
    }
    public static Review compare(ProcessBlueprint before,ProcessBlueprint after,
                                 BlueprintImpactAnalyzer.Impact impact) {
        if(!before.id().equals(after.id())||before.revision()>=after.revision())
            throw new IllegalArgumentException("INVALID_REVISION_RANGE");
        var diff=BlueprintDiff.compare(before,after);
        return new Review(before.revision(),after.revision(),
                diff.changedUnitIds(),diff.changedAssetIds(),impact.affectedUnits(),
                impact.reasons(),"NOT_RECORDED","NOT_RECORDED",false);
    }
}
