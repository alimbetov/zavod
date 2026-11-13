package kz.zavod.blueprint;

import kz.zavod.blueprint.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static kz.zavod.blueprint.domain.ProcessBlueprint.*;
import static org.junit.jupiter.api.Assertions.*;

/** RED-INT-06 / RED-INT-08. Regression oracles independent of the implementation. */
class IntelligenceCoreRedTest {
    private static ProcessUnit unit(String id, List<Equipment> equipment, List<ResourceUse> resources) {
        return new ProcessUnit(id, UnitKind.LINE, "test.v1", equipment,
                List.of(), List.of(), resources, List.of(), false);
    }

    private static ProcessBlueprint blueprint(UUID id, long revision, List<ProcessUnit> units,
                                               List<SharedAsset> assets) {
        return new ProcessBlueprint(id, revision, 10, units, assets, List.of(), List.of());
    }

    @Test
    void redInt06_resourceSumMustNotWrapToNegativeOrPassValidation() {
        UUID id = UUID.randomUUID();
        var resource = new SharedAsset("power", Integer.MAX_VALUE, AssetMode.SHARED_CAPACITY);
        var units = List.of(
                unit("A", List.of(), List.of(new ResourceUse("power", Integer.MAX_VALUE))),
                unit("B", List.of(), List.of(new ResourceUse("power", Integer.MAX_VALUE))));
        var snapshot = blueprint(id, 1, units, List.of(resource));

        var policy = ResourcePolicyValidator.inspect(snapshot);
        assertEquals(1, policy.size(), "Two positive demands exceed resource capacity");
        assertEquals("RESOURCE_OVERCOMMIT", policy.get(0).code());
        assertEquals(2L * Integer.MAX_VALUE, policy.get(0).totalDemand(),
                "Demand aggregation must use a wide integer");
        assertTrue(BlueprintReadiness.blockers(snapshot, kz.zavod.catalog.EquipmentCatalog.standard())
                .stream().anyMatch(f -> f.code().equals("RESOURCE_OVERCOMMIT")));
        var impact = BlueprintImpactAnalyzer.analyze(snapshot, Set.of("A"));
        assertEquals(1, impact.contention().size());
        assertEquals(2L * Integer.MAX_VALUE, impact.contention().get(0).configuredDemand());
    }

    @Test
    void redInt08_portOrderMustNotProducePhantomEngineeringDiff() {
        UUID id = UUID.randomUUID();
        var in = new Port("in", Direction.IN, FlowKind.MATERIAL, "water", 0, 1);
        var out = new Port("out", Direction.OUT, FlowKind.MATERIAL, "water", 0, 1);
        var initial = blueprint(id, 1, List.of(unit("L1",
                List.of(new Equipment("E1", "water-pump", 1, List.of(in, out))), List.of())), List.of());
        var onlyPortOrderChanged = blueprint(id, 2, List.of(unit("L1",
                List.of(new Equipment("E1", "water-pump", 1, List.of(out, in))), List.of())), List.of());

        assertTrue(BlueprintDiff.compare(initial, onlyPortOrderChanged).changedUnitIds().isEmpty(),
                "Stable port keys, not List order, determine engineering identity");
        assertTrue(BlueprintImpactAnalyzer.analyzeChange(initial, onlyPortOrderChanged)
                .affectedUnits().isEmpty(), "Pure port permutation has no physical impact");
    }
}
