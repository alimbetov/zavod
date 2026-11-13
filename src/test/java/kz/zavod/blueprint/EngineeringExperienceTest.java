package kz.zavod.blueprint;

import java.util.*;
import kz.zavod.blueprint.domain.*;
import kz.zavod.catalog.EquipmentCatalog;
import kz.zavod.studio.EngineeringReadCache;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;

class EngineeringExperienceTest {
    private final EquipmentCatalog catalog=EquipmentCatalog.standard();

    @Test void guidedStarterHasVerifiedTopologyButCannotClaimPhysicalCertification() {
        UUID id=UUID.randomUUID();
        var starter=GuidedBlueprintBuilder.pumpStarter(id,2,10,"L001",catalog);
        assertEquals(3,starter.units().get(0).equipment().size());
        assertTrue(BlueprintReadiness.blockers(starter,catalog).isEmpty());
        var report=EngineeringGoldenModel.inspect(starter,catalog);
        assertTrue(report.structurallyQualified(),report.findings().toString());
        assertFalse(report.physicallyQualified());
        assertEquals("ENGINEERING_PREVIEW",HmiPreviewGenerator.generate(starter,Set.of("L001")).mode());
        assertThrows(IllegalArgumentException.class,
                ()->GuidedBlueprintBuilder.pumpStarter(id,2,10,"../evil",catalog));
    }

    @Test void missingEquipmentIsExplainedAndGoldenFailsClosed() {
        var starter=GuidedBlueprintBuilder.pumpStarter(UUID.randomUUID(),2,10,"L001",catalog);
        var unit=starter.units().get(0);
        var broken=new ProcessUnit(unit.id(),unit.kind(),unit.profileKey(),
                unit.equipment().stream().filter(x->!x.typeKey().equals("water-pump")).toList(),
                List.of(),unit.boundaries(),unit.resources(),unit.measurements(),false);
        var snapshot=new ProcessBlueprint(starter.id(),3,10,List.of(broken),
                List.of(),List.of(),List.of());
        assertFalse(EngineeringGoldenModel.inspect(snapshot,catalog).structurallyQualified());
        assertTrue(EngineeringGoldenModel.inspect(snapshot,catalog).findings().stream()
                .anyMatch(x->x.code().equals("GOLDEN_MISSING_PUMP")));
        assertTrue(ExplainableValidation.inspect(snapshot,catalog).stream()
                .anyMatch(x->x.code().equals("GOLDEN_MISSING_PUMP")
                        &&!x.suggestedAction().isBlank()));
    }

    @Test void readCacheOnlyStoresImmutableComputedDataAndChangeReviewIsConservative() {
        UUID id=UUID.randomUUID();
        var before=GuidedBlueprintBuilder.pumpStarter(id,2,10,"L001",catalog);
        var after=new ProcessBlueprint(id,3,10,before.units(),before.sharedAssets(),
                before.transfers(),before.dependencies());
        var cache=new EngineeringReadCache();
        assertEquals(15,cache.equipmentTypes(catalog).size());
        cache.equipmentTypes(catalog);
        assertEquals(1,cache.catalogHits());
        var first=cache.impact(before,after);
        var again=cache.impact(before,after);
        assertEquals(first,again);
        assertEquals(1,cache.impactHits());
        var change=EngineeringChangeManagement.compare(before,after,again);
        assertEquals("NOT_RECORDED",change.changedBy());
        assertFalse(change.canRestoreWithoutApproval());
    }

    @Test void hmiPreviewNeverClaimsLiveEquipmentStateAndOnlyRendersSelectedUnits() {
        UUID id=UUID.randomUUID();
        var first=GuidedBlueprintBuilder.pumpStarter(id,2,10,"A",catalog);
        var second=GuidedBlueprintBuilder.pumpStarter(id,2,10,"B",catalog);
        var both=new ProcessBlueprint(id,2,10,List.of(first.units().get(0),second.units().get(0)),
                List.of(),List.of(),List.of());
        var preview=HmiPreviewGenerator.generate(both,Set.of("A"));
        assertFalse(preview.live());
        assertEquals(1,preview.lines().size());
        assertEquals("A",preview.lines().get(0).unitId());
        assertTrue(preview.lines().get(0).equipment().stream()
                .allMatch(x->x.state().equals("UNKNOWN_NO_TELEMETRY")));
    }
}
